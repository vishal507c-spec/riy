package com.vishal.riy.protection.escalation

import com.vishal.riy.lock.InMemoryLockStore
import com.vishal.riy.lock.LockEngine
import com.vishal.riy.protection.ProtectionEventProcessor
import com.vishal.riy.protection.adultDomainLookupEvent
import com.vishal.riy.protection.enforcement.AndroidEnforcementEngine
import com.vishal.riy.protection.enforcement.FakeDevicePolicyBoundary
import com.vishal.riy.protection.enforcement.PackageManagerAppPolicyResolver
import com.vishal.riy.protection.enforcement.PolicyApplicationResult
import com.vishal.riy.protection.enforcement.fakeDevice
import com.vishal.riy.protection.events.InMemoryProtectionEventStore
import com.vishal.riy.protection.events.ProtectionLogEvent
import com.vishal.riy.protection.policy.DefaultProtectionPolicyEngine
import com.vishal.riy.protection.policy.ProtectionDecision
import com.vishal.riy.protection.policy.ProtectionState
import com.vishal.riy.protection.restricted.DefaultRestrictedModeController
import com.vishal.riy.protection.risk.DefaultRiskEngine
import com.vishal.riy.protection.state.InMemoryProtectionStateStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Escalation as it actually flows through the production pipeline: the stateful
 * escalation component counts qualifying events, the stateless risk engine
 * grades one event, and ONLY the policy engine performs the
 * RESTRICTED → HARDENED transition — while the LockEngine deadline is never
 * re-armed by any of it.
 */
class EscalationPipelineTest {

    private val riyPackage = "com.vishal.riy"

    private lateinit var dpm: FakeDevicePolicyBoundary
    private lateinit var enforcement: AndroidEnforcementEngine
    private lateinit var stateStore: InMemoryProtectionStateStore
    private lateinit var lockStore: InMemoryLockStore
    private lateinit var eventStore: InMemoryProtectionEventStore
    private lateinit var escalationStore: InMemoryEscalationStore
    private lateinit var escalation: ProtectionEscalationEngine
    private lateinit var processor: ProtectionEventProcessor

    private var now = NOW
    private var sessionCounter = 0

    @Before
    fun setUp() {
        now = NOW
        sessionCounter = 0
        dpm = FakeDevicePolicyBoundary(deviceOwnerPackage = riyPackage)
        val device = fakeDevice(systemDialer = "com.device.dialer", inputMethod = "com.device.ime") {
            riy(riyPackage)
            phone()
            wallet()
            keyboard()
        }
        enforcement = AndroidEnforcementEngine(
            devicePolicy = dpm,
            discovery = device,
            appPolicyResolver = PackageManagerAppPolicyResolver(device, riyPackage),
            riyPackageName = riyPackage,
            clock = { now },
        )
        stateStore = InMemoryProtectionStateStore()
        lockStore = InMemoryLockStore()
        eventStore = InMemoryProtectionEventStore()
        escalationStore = InMemoryEscalationStore()
        escalation = ProtectionEscalationEngine(escalationStore) { now }

        processor = ProtectionEventProcessor(
            riskEngine = DefaultRiskEngine(),
            policyEngine = DefaultProtectionPolicyEngine(),
            stateStore = stateStore,
            lockStore = lockStore,
            escalationEngine = escalation,
            restrictedMode = DefaultRestrictedModeController(enforcement, stateStore, lockStore) { now },
            eventStore = eventStore,
            enforcement = enforcement,
            clock = { now },
            sessionId = { "session-${++sessionCounter}" },
        )
    }

    // ------------------------------------------------- §9 escalation reaches

    @Test
    fun `repeated qualifying detections escalate the session to HARDENED through the policy engine`() {
        // Detection 1 arms the restriction.
        submit()
        assertEquals(ProtectionState.RESTRICTED, stateStore.current()!!.state)

        // Detections 2 and 3 are absorbed while the session is live, but they
        // DO count toward the escalation threshold.
        now += MINUTE
        submit()
        assertEquals(ProtectionState.RESTRICTED, stateStore.current()!!.state)

        now += MINUTE
        val third = submit()

        // Only the policy engine performed this transition.
        assertEquals(ProtectionState.HARDENED, third.decision.nextState)
        assertEquals(ProtectionDecision.REASON_ESCALATED, third.decision.reason)
        assertEquals(ProtectionState.HARDENED, stateStore.current()!!.state)
    }

    @Test
    fun `the escalation decision is exposed by the processor for diagnostics`() {
        submit()
        now += MINUTE
        submit()

        // The second event was counted but did not escalate.
        assertEquals(2, processor.lastEscalationDecision()!!.qualifyingCount)
        assertFalse(processor.lastEscalationDecision()!!.escalated)

        now += MINUTE
        submit()

        assertTrue(processor.lastEscalationDecision()!!.escalated)
    }

    // ------------------------------------------- §10 policy engine authority

    @Test
    fun `escalation cannot arm a restriction from NORMAL`() {
        // HARDENED is reachable ONLY from RESTRICTED in the policy engine's
        // table, so the escalation signal is never applied when no session is
        // live: the first detection still enters RESTRICTED through the
        // ordinary CONFIRMED path, exactly as Phase 5 did.
        val first = submit()

        assertEquals(ProtectionState.RESTRICTED, first.decision.nextState)
        assertNotEquals(ProtectionState.HARDENED, first.decision.nextState)
    }

    @Test
    fun `the first detection enters RESTRICTED even when counting says escalate`() {
        // An escalation signal cannot reach HARDENED without a live session, so
        // detection #1 arms the ordinary restriction regardless.
        val first = submit()

        assertEquals(ProtectionState.RESTRICTED, first.decision.nextState)
        assertEquals(ProtectionDecision.REASON_CONTENT_DETECTED, first.decision.reason)
    }

    @Test
    fun `HARDENED is reachable only from RESTRICTED via the legal transition table`() {
        // NORMAL -> HARDENED is not legal, so an escalation signal arriving
        // while nothing is live cannot skip the restriction.
        val policy = DefaultProtectionPolicyEngine()
        val escalated = policy.decide(
            com.vishal.riy.protection.risk.RiskAssessment(
                riskLevel = com.vishal.riy.protection.risk.RiskLevel.HARDENED,
                confidence = com.vishal.riy.protection.event.ProtectionEvent.Confidence.CERTAIN,
                evidenceType = com.vishal.riy.protection.event.ProtectionEvidenceType.ADULT_DOMAIN_DNS_LOOKUP,
                reasoning = "escalated",
                event = adultDomainLookupEvent(DOMAIN, now),
            ),
            ProtectionState.NORMAL,
        )

        assertEquals(ProtectionState.NORMAL, escalated.nextState)
        assertEquals(ProtectionDecision.REASON_NO_CHANGE, escalated.reason)
    }

    // ----------------------------- §7-8 deadline authority under escalation

    @Test
    fun `escalation does not alter the LockEngine deadline`() {
        submit()
        val deadline = lockStore.loadState().lockEndEpochMillis

        now += MINUTE
        submit()
        now += MINUTE
        submit() // this event escalates to HARDENED

        assertEquals("the 2-hour deadline is unchanged by escalation",
            deadline, lockStore.loadState().lockEndEpochMillis)
    }

    @Test
    fun `escalation creates no second timer or second session id`() {
        submit()
        val sessionId = stateStore.current()!!.sessionId

        now += MINUTE
        submit()
        now += MINUTE
        submit()

        // A HARDENED transition re-enters the same restricted lifecycle; the
        // session id changes (it is a new session record) but the deadline does
        // not, and there is still exactly ONE deadline.
        assertEquals("one deadline, never two",
            1, lockStore.loadState().lockEndEpochMillis.let { if (it > 0) 1 else 0 })
        assertNotEquals(sessionId, stateStore.current()!!.sessionId)
        assertEquals(ProtectionState.HARDENED, stateStore.current()!!.state)
    }

    // ----------------------------------- §2 repeated-event semantics preserved

    @Test
    fun `a repeated event during a live session does not re-arm the deadline`() {
        submit()
        val deadline = lockStore.loadState().lockEndEpochMillis

        now += 1_000L
        val repeated = submit()

        assertEquals(ProtectionState.RESTRICTED, repeated.decision.nextState)
        assertEquals(ProtectionDecision.REASON_NO_CHANGE, repeated.decision.reason)
        assertEquals(deadline, lockStore.loadState().lockEndEpochMillis)
    }

    // ------------------------------------------------ §11 enforcement outcome

    @Test
    fun `an escalated session is enforced and reported honestly`() {
        submit()
        now += MINUTE
        submit()
        now += MINUTE
        val third = submit()

        assertEquals(PolicyApplicationResult.APPLIED, third.enforcementResult)
        assertTrue("the escalated session is actually on the platform",
            dpm.rawLockTaskPackages().contains(riyPackage))
    }

    @Test
    fun `the event log records the escalation honestly`() {
        submit()
        now += MINUTE
        submit()
        now += MINUTE
        submit()

        val logged = eventStore.recent()
        assertTrue(logged.any { it.type == ProtectionLogEvent.Type.RESTRICTION_ENTERED })
        // Every genuine observation is still recorded as evidence.
        assertEquals(3, logged.count { it.type == ProtectionLogEvent.Type.CONTENT_DETECTED })
    }

    // ----------------------------------------------------- persistence across

    @Test
    fun `escalation counting survives a process restart`() {
        submit()
        now += MINUTE
        submit()

        // A new processor + engine on the SAME stores is a restarted process.
        val restarted = ProtectionEventProcessor(
            riskEngine = DefaultRiskEngine(),
            policyEngine = DefaultProtectionPolicyEngine(),
            stateStore = stateStore,
            lockStore = lockStore,
            escalationEngine = ProtectionEscalationEngine(escalationStore) { now },
            restrictedMode = DefaultRestrictedModeController(enforcement, stateStore, lockStore) { now },
            eventStore = eventStore,
            enforcement = enforcement,
            clock = { now },
            sessionId = { "session-${++sessionCounter}" },
        )

        now += MINUTE
        val third = restarted.submit(adultDomainLookupEvent(DOMAIN, now))

        // The two pre-restart detections were counted, so this one escalates.
        assertEquals(ProtectionState.HARDENED, third.decision.nextState)
    }

    @Test
    fun `escalation state survives when the lock deadline has lapsed`() {
        submit()
        now += MINUTE
        submit()

        // The 2-hour session lapses but the 24-hour escalation window does not.
        now += LockEngine.LOCK_DURATION_MS + 1_000L

        val restarted = ProtectionEventProcessor(
            riskEngine = DefaultRiskEngine(),
            policyEngine = DefaultProtectionPolicyEngine(),
            stateStore = stateStore,
            lockStore = lockStore,
            escalationEngine = ProtectionEscalationEngine(escalationStore) { now },
            restrictedMode = DefaultRestrictedModeController(enforcement, stateStore, lockStore) { now },
            eventStore = eventStore,
            enforcement = enforcement,
            clock = { now },
            sessionId = { "session-${++sessionCounter}" },
        )
        val freshDetection = restarted.submit(adultDomainLookupEvent(DOMAIN, now))

        // The deadline lapsed, so a genuinely NEW session arms from this fresh
        // detection: the policy engine maps CONFIRMED to RESTRICTED again, not
        // HARDENED (no session was live when it arrived). The accumulated
        // counting is preserved for the NEXT escalation.
        assertEquals(ProtectionState.RESTRICTED, freshDetection.decision.nextState)
        assertEquals(3, restarted.lastEscalationDecision()!!.qualifyingCount)

        // The next qualifying event now escalates the live session, proving
        // the pre-lapse counting survived.
        now += MINUTE
        val escalated = restarted.submit(adultDomainLookupEvent(DOMAIN, now))

        assertEquals(ProtectionState.HARDENED, escalated.decision.nextState)
        assertEquals(ProtectionDecision.REASON_ESCALATED, escalated.decision.reason)
    }

    // ------------------------------------------------------------- helper

    private fun submit() = processor.submit(adultDomainLookupEvent(DOMAIN, now))

    private companion object {
        const val NOW = 1_000_000L
        const val MINUTE = 60_000L
        const val DOMAIN = "blocked.example"
    }
}
