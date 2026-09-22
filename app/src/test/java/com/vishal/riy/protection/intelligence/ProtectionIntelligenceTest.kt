package com.vishal.riy.protection.intelligence

import com.vishal.riy.blocker.Blocklist
import com.vishal.riy.lock.InMemoryLockStore
import com.vishal.riy.lock.LockEngine
import com.vishal.riy.protection.ProtectionEventProcessor
import com.vishal.riy.protection.enforcement.AndroidEnforcementEngine
import com.vishal.riy.protection.enforcement.FakeDevicePolicyBoundary
import com.vishal.riy.protection.enforcement.PackageManagerAppPolicyResolver
import com.vishal.riy.protection.enforcement.PolicyApplicationResult
import com.vishal.riy.protection.enforcement.fakeDevice
import com.vishal.riy.protection.escalation.InMemoryEscalationStore
import com.vishal.riy.protection.escalation.ProtectionEscalationEngine
import com.vishal.riy.protection.event.ProtectionEvidenceType
import com.vishal.riy.protection.events.InMemoryProtectionEventStore
import com.vishal.riy.protection.policy.DefaultProtectionPolicyEngine
import com.vishal.riy.protection.policy.ProtectionDecision
import com.vishal.riy.protection.policy.ProtectionPolicy
import com.vishal.riy.protection.policy.ProtectionState
import com.vishal.riy.protection.recovery.DefaultRecoveryService
import com.vishal.riy.protection.recovery.RecoveryOutcome
import com.vishal.riy.protection.restricted.DefaultRestrictedModeController
import com.vishal.riy.protection.risk.DefaultRiskEngine
import com.vishal.riy.protection.state.InMemoryProtectionStateStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * The intelligence layer as it actually flows through the production pipeline.
 * These are the Phase 10 acceptance scenarios: ordinary activity produces
 * nothing, ambiguous activity does not lock, a definitive signal arms the
 * EXISTING pipeline, and corroborated ambiguous evidence enters through the same
 * pipeline without creating any second owner.
 */
class ProtectionIntelligenceTest {

    private val riyPackage = "com.vishal.riy"

    // The production blocklist classification behaviour, exercised directly so
    // the tiering these tests rely on is the real one.
    private val blocklist = Blocklist(
        listOf("pornhub.com", "xvideos.com", "sex.com", "nude.xxx"),
    )

    private lateinit var enforcement: AndroidEnforcementEngine
    private lateinit var stateStore: InMemoryProtectionStateStore
    private lateinit var lockStore: InMemoryLockStore
    private lateinit var eventStore: InMemoryProtectionEventStore
    private lateinit var escalationStore: InMemoryEscalationStore
    private lateinit var observationStore: InMemoryObservationStore
    private lateinit var intelligence: ProtectionIntelligence

    private var now = NOW
    private var sessionCounter = 0
    private var eventIdCounter = 0

    @Before
    fun setUp() {
        now = NOW
        sessionCounter = 0
        eventIdCounter = 0
        enforcement = newEnforcement(FakeDevicePolicyBoundary(deviceOwnerPackage = riyPackage))
        stateStore = InMemoryProtectionStateStore()
        lockStore = InMemoryLockStore()
        eventStore = InMemoryProtectionEventStore()
        escalationStore = InMemoryEscalationStore()
        observationStore = InMemoryObservationStore { now }

        intelligence = newIntelligence(enforcement)
    }

    // ------------------------------------------- §1-3 ordinary activity: nothing

    @Test
    fun `ordinary Google activity produces no observation and no protection event`() {
        assertNull(intelligence.onAdultDomainLookup("google.com"))

        assertEquals(0, intelligence.observations().size)
        assertEquals(ProtectionState.NORMAL, currentBackendState())
        assertNotRestricted()
    }

    @Test
    fun `ordinary Chrome activity produces no observation and no protection event`() {
        assertNull(intelligence.onAdultDomainLookup("chrome.com"))

        assertEquals(0, intelligence.observations().size)
        assertNotRestricted()
    }

    @Test
    fun `ordinary YouTube activity produces no observation and no protection event`() {
        assertNull(intelligence.onAdultDomainLookup("youtube.com"))

        assertEquals(0, intelligence.observations().size)
        assertNotRestricted()
    }

    @Test
    fun `ordinary medical, news and adult-education activity produces no protection event`() {
        // "adult" as a substring is NOT a match — the token rule requires a whole
        // label of the registrable part, so adult-health education stays clear.
        assertNull(intelligence.onAdultDomainLookup("mayoclinic.org"))
        assertNull(intelligence.onAdultDomainLookup("adulteducation.example.org"))
        assertNull(intelligence.onAdultDomainLookup("sussex.ac.uk"))

        assertEquals(0, intelligence.observations().size)
        assertNotRestricted()
    }

    // --------------------------------------- §4 one ambiguous signal: no lock

    @Test
    fun `one ambiguous signal alone does not lock the device`() {
        // A whole-label token match: recorded, never acted on alone.
        val result = intelligence.onAdultDomainLookup("nsfw.example")

        assertNull("an uncorroborated suspect produces no event", result)
        assertEquals(1, intelligence.observations().size)
        assertNotRestricted()
        assertEquals(LockEngine.NO_LOCK, lockStore.loadState().lockEndEpochMillis)
    }

    // --------------------------- §5 a definitive signal: the existing pipeline

    @Test
    fun `a known adult-domain signal arms the existing pipeline unchanged`() {
        val result = intelligence.onAdultDomainLookup("pornhub.com")

        assertNotNull(result)
        assertEquals(ProtectionState.RESTRICTED, result!!.decision.nextState)
        assertEquals(ProtectionDecision.REASON_CONTENT_DETECTED, result.decision.reason)
        assertEquals(ProtectionState.RESTRICTED, currentBackendState())

        // The ONE deadline, owned by LockEngine: exactly 2 hours from now.
        assertEquals(
            now + LockEngine.LOCK_DURATION_MS,
            lockStore.loadState().lockEndEpochMillis,
        )
        // The session mirrors that same deadline — never a second one.
        assertEquals(
            lockStore.loadState().lockEndEpochMillis,
            stateStore.current()!!.expiryTime,
        )
        assertEquals(PolicyApplicationResult.APPLIED, result.enforcementResult)
    }

    @Test
    fun `a suspect and then a definitive domain for a different host arms the restriction once`() {
        // The suspect is recorded but not yet evidence...
        assertNull(intelligence.onAdultDomainLookup("nsfw.example"))
        assertNotRestricted()

        // ...the definitive match arms the pipeline, exactly as Phase 1 did.
        now += 30_000
        val armed = intelligence.onAdultDomainLookup("pornhub.com")

        assertEquals(ProtectionState.RESTRICTED, armed!!.decision.nextState)
        assertEquals(
            now + LockEngine.LOCK_DURATION_MS,
            lockStore.loadState().lockEndEpochMillis,
        )
    }

    // ------------------------- §6 corroborated signals: correct correlation

    @Test
    fun `repeated corroborating ambiguous signals produce exactly one confirmed event`() {
        assertNull(intelligence.onAdultDomainLookup("nsfw.example"))

        now += 30_000
        val confirmed = intelligence.onAdultDomainLookup("nude.example")

        assertNotNull(confirmed)
        assertEquals(ProtectionState.RESTRICTED, confirmed!!.decision.nextState)
        assertEquals(
            "corroborated evidence enters through the existing event contract",
            ProtectionEvidenceType.CORROBORATED_ADULT_CONTENT,
            confirmed.decision.assessment?.evidenceType,
        )
        assertEquals(
            now + LockEngine.LOCK_DURATION_MS,
            lockStore.loadState().lockEndEpochMillis,
        )
    }

    // ------------------------------- §7 duplicate signal: no duplicate event

    @Test
    fun `a duplicate signal inside the dedup window creates no duplicate event`() {
        intelligence.onAdultDomainLookup("pornhub.com")
        val firstDeadline = lockStore.loadState().lockEndEpochMillis

        // The same domain a second later is the same observation.
        now += 1_000
        val duplicate = intelligence.onAdultDomainLookup("pornhub.com")

        assertNull("the A/AAAA/retry burst is one observation", duplicate)
        assertEquals(
            "the deadline is not re-armed by a duplicate",
            firstDeadline,
            lockStore.loadState().lockEndEpochMillis,
        )
    }

    // -------------------- §8 multiple signals in one session: aggregation

    @Test
    fun `multiple signals in the same session aggregate without re-arming the deadline`() {
        intelligence.onAdultDomainLookup("pornhub.com")
        val firstDeadline = lockStore.loadState().lockEndEpochMillis

        now += MINUTE
        intelligence.onAdultDomainLookup("xvideos.com")

        // A live session absorbs a second distinct domain: the policy engine's
        // stickiness, not a re-arm.
        assertEquals(
            "a live session keeps its original deadline",
            firstDeadline,
            lockStore.loadState().lockEndEpochMillis,
        )
        assertEquals(ProtectionState.RESTRICTED, currentBackendState())
    }

    // --------------------- §9 old evidence outside the window: ignored

    @Test
    fun `evidence outside the correlation window does not corroborate a suspect`() {
        assertNull(intelligence.onAdultDomainLookup("nsfw.example"))

        now += 6 * MINUTE + 1_000 // beyond the 5-minute correlation window
        val stale = intelligence.onAdultDomainLookup("nude.example")

        assertNull("stale corroboration must not confirm", stale)
        assertNotRestricted()
        assertEquals(LockEngine.NO_LOCK, lockStore.loadState().lockEndEpochMillis)
    }

    // --------------------- §10-11 confirmed event and the HARDENED path

    @Test
    fun `a third confirmed attempt escalates through the existing HARDENED path`() {
        // Three qualifying detections, each separated so they are not deduped.
        repeat(3) { index ->
            if (index > 0) now += MINUTE
            intelligence.onAdultDomainLookup(QUALIFYING_DOMAINS[index])
        }

        val session = stateStore.current()!!
        assertEquals(
            "the third qualifying detection escalates via the existing policy engine",
            ProtectionState.HARDENED,
            session.state,
        )
        // HARDENED reuses the SAME deadline: there is deliberately no second one.
        assertEquals(
            lockStore.loadState().lockEndEpochMillis,
            session.expiryTime,
        )
    }

    // --------------------- §12-13 the deadline authority is unchanged

    @Test
    fun `LockEngine remains the only deadline authority and there is no second timer`() {
        intelligence.onAdultDomainLookup("pornhub.com")

        // The only deadline in the system is the LockStore value, and the
        // session is its mirror — so a second timer is structurally impossible.
        val deadline = lockStore.loadState().lockEndEpochMillis
        assertNotEquals(LockEngine.NO_LOCK, deadline)
        assertEquals(deadline, stateStore.current()!!.expiryTime)

        // An observation carries no deadline-bearing field at all.
        intelligence.observations().forEach { observation ->
            assertFalse(observation.toString().contains("expiry"))
        }
    }

    // --------------------- §14 process restart preserves state

    @Test
    fun `process restart preserves both the restriction and a pending observation`() {
        assertNull(intelligence.onAdultDomainLookup("nsfw.example"))

        // A fresh intelligence layer over the SAME persisted stores is exactly
        // what a recreated process sees.
        now += 30_000
        val restored = newIntelligence(enforcement)
        val confirmed = restored.onAdultDomainLookup("nude.example")

        assertNotNull(
            "the pre-restart observation still corroborates after restart",
            confirmed,
        )
        assertEquals(ProtectionState.RESTRICTED, confirmed!!.decision.nextState)
    }

    @Test
    fun `process restart preserves an armed restriction and its deadline`() {
        intelligence.onAdultDomainLookup("pornhub.com")
        val deadline = lockStore.loadState().lockEndEpochMillis

        newIntelligence(enforcement)

        assertEquals("the armed deadline survives a restart", deadline, lockStore.loadState().lockEndEpochMillis)
        assertEquals(ProtectionState.RESTRICTED, stateStore.current()!!.state)
    }

    // --------------------- §15 recovery preserves state, no duplicate deadline

    @Test
    fun `recovery preserves an armed restriction without creating a duplicate deadline`() {
        intelligence.onAdultDomainLookup("pornhub.com")
        val deadline = lockStore.loadState().lockEndEpochMillis

        // The recovery orchestrator over the SAME shared stores.
        val service = DefaultRecoveryService(
            restrictedMode = DefaultRestrictedModeController(enforcement, stateStore, lockStore) { now },
            lockStore = lockStore,
            enforcement = enforcement,
            eventStore = eventStore,
            clock = { now },
        )
        val result = service.recoverWithResult()

        assertEquals(RecoveryOutcome.RESTORED, result.outcome)
        assertEquals(
            "recovery re-reads the deadline and never writes one",
            deadline,
            lockStore.loadState().lockEndEpochMillis,
        )
        // The session is still a faithful mirror of that one deadline.
        assertEquals(deadline, stateStore.current()!!.expiryTime)
    }

    // --------------------- §16 a mismatch is never reported as success

    @Test
    fun `an enforcement mismatch is reported honestly and never as a success`() {
        // The platform refuses the restrictive allowlist.
        val failingDpm = FakeDevicePolicyBoundary(deviceOwnerPackage = riyPackage).apply {
            rejectSetLockTaskPackages = true
        }
        val failingEngine = newEnforcement(failingDpm)
        val failingIntelligence = newIntelligence(failingEngine)

        val result = failingIntelligence.onAdultDomainLookup("pornhub.com")

        assertNotNull(result)
        assertNotEquals(
            "a failed apply is never dressed up as success",
            PolicyApplicationResult.APPLIED,
            result!!.enforcementResult,
        )
    }

    // --------------------- §17 the UI never exposes sensitive raw content

    @Test
    fun `the event log carries no raw sensitive content beyond the minimum the contract defines`() {
        intelligence.onAdultDomainLookup("pornhub.com")

        val events = eventStore.recent()
        assertTrue(events.isNotEmpty())
        // The log message is evidence-derived wording only: no URL, no query
        // string, no path, no page content, no private text.
        events.forEach { event ->
            assertFalse("no URL scheme in the log: ${event.message}", event.message.contains("http"))
            assertFalse("no query string in the log: ${event.message}", event.message.contains("?"))
            assertFalse("no path separator in the log: ${event.message}", event.message.contains("/"))
        }
    }

    @Test
    fun `observation metadata stores only the matched domain and match class`() {
        intelligence.onAdultDomainLookup("nsfw.example")

        val observation = intelligence.observations().single()
        assertEquals("nsfw.example", observation.domain)
        assertEquals(com.vishal.riy.blocker.BlocklistMatch.SUSPECT, observation.matchClass)
        assertEquals(
            ProtectionPolicy.CURRENT_POLICY_VERSION,
            observation.policyVersion,
        )
    }

    // ------------------------------------------------------------- helpers

    private fun assertNotRestricted() {
        val session = stateStore.current()
        assertTrue(
            "no restricted session should exist (was $session)",
            session == null || session.state == ProtectionState.NORMAL ||
                session.state == ProtectionState.SUSPICIOUS,
        )
    }

    private fun currentBackendState(): ProtectionState =
        stateStore.current()?.state ?: ProtectionState.NORMAL

    private fun newIntelligence(enforcement: AndroidEnforcementEngine): ProtectionIntelligence =
        ProtectionIntelligence(
            blocklist = blocklist,
            correlator = EvidenceCorrelator(),
            store = observationStore,
            pipeline = newProcessor(enforcement),
            clock = { now },
            eventId = { "event-${++eventIdCounter}" },
        )

    private fun newProcessor(enforcement: AndroidEnforcementEngine): ProtectionEventProcessor =
        ProtectionEventProcessor(
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

    private fun newEnforcement(dpm: FakeDevicePolicyBoundary): AndroidEnforcementEngine {
        val device = fakeDevice(systemDialer = "com.device.dialer", inputMethod = "com.device.ime") {
            riy(riyPackage)
            phone()
            wallet()
            keyboard()
        }
        return AndroidEnforcementEngine(
            devicePolicy = dpm,
            discovery = device,
            appPolicyResolver = PackageManagerAppPolicyResolver(device, riyPackage),
            riyPackageName = riyPackage,
            clock = { now },
        )
    }

    private companion object {

        const val NOW: Long = 1_700_000_000_000L
        const val MINUTE: Long = 60_000L

        // Three DISTINCT definitive domains so each counts as a new detection.
        val QUALIFYING_DOMAINS = listOf("pornhub.com", "xvideos.com", "sex.com")
    }
}
