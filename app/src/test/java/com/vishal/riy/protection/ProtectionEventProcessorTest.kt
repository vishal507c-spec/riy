package com.vishal.riy.protection

import com.vishal.riy.lock.InMemoryLockStore
import com.vishal.riy.lock.LockEngine
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
import com.vishal.riy.protection.risk.RiskLevel
import com.vishal.riy.protection.state.InMemoryProtectionStateStore
import com.vishal.riy.protection.state.ProtectionSession
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import kotlin.time.DurationUnit
import kotlin.time.toDuration

/**
 * THE Phase 5 integration test. It wires the REAL production components —
 * [DefaultRiskEngine], [DefaultProtectionPolicyEngine],
 * [DefaultRestrictedModeController] and the REAL [AndroidEnforcementEngine] —
 * to in-memory stores and the two existing fake platform boundaries, so the
 * whole chain from a genuine DNS event to a verified platform restriction is
 * proven WITHOUT a device:
 *
 *     adultDomainLookupEvent → ProtectionEvent
 *         → RiskEngine → RiskAssessment
 *         → ProtectionPolicyEngine → ProtectionDecision
 *         → ProtectionSession / ProtectionStateStore
 *         → RestrictedModeController
 *         → AndroidEnforcementEngine → DevicePolicyBoundary
 */
class ProtectionEventProcessorTest {

    private val riyPackage = "com.vishal.riy"

    private lateinit var dpm: FakeDevicePolicyBoundary
    private lateinit var enforcement: AndroidEnforcementEngine
    private lateinit var stateStore: InMemoryProtectionStateStore
    private lateinit var lockStore: InMemoryLockStore
    private lateinit var eventStore: InMemoryProtectionEventStore
    private lateinit var restricted: DefaultRestrictedModeController
    private lateinit var processor: ProtectionEventProcessor

    private var now = NOW
    private var sessionCounter = 0

    @Before
    fun setUp() {
        now = NOW
        sessionCounter = 0
        buildProcessor()
    }

    private fun buildProcessor(owner: Boolean = true) {
        dpm = FakeDevicePolicyBoundary(deviceOwnerPackage = if (owner) riyPackage else null)
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
        restricted = DefaultRestrictedModeController(enforcement, stateStore, lockStore) { now }
        processor = ProtectionEventProcessor(
            riskEngine = DefaultRiskEngine(),
            policyEngine = DefaultProtectionPolicyEngine(),
            stateStore = stateStore,
            lockStore = lockStore,
            restrictedMode = restricted,
            eventStore = eventStore,
            enforcement = enforcement,
            clock = { now },
            sessionId = { "session-${++sessionCounter}" },
        )
    }

    // --------------------------------------------------------------- §1-2

    @Test
    fun `a genuine adult domain lookup becomes the defined protection event`() {
        val event = adultDomainLookupEvent(DOMAIN, NOW)

        assertEquals("ADULT_DOMAIN_DNS_LOOKUP is the only production evidence type",
            com.vishal.riy.protection.event.ProtectionEvidenceType.ADULT_DOMAIN_DNS_LOOKUP,
            event.evidenceType)
        assertEquals(com.vishal.riy.protection.event.ProtectionEventSource.DNS_FILTER, event.source)
        assertEquals(DOMAIN, event.metadata[com.vishal.riy.protection.event.ProtectionEvent.META_DOMAIN])
        assertEquals("a blocklist match is certain, not probable",
            com.vishal.riy.protection.event.ProtectionEvent.Confidence.CERTAIN, event.confidence)
        assertEquals(NOW, event.timestamp)
    }

    // ------------------------------------------------------------- §3-5

    @Test
    fun `a normal device enters RESTRICTED from a single genuine event`() {
        val result = processor.submit(adultDomainLookupEvent(DOMAIN, now))

        // §3 the risk engine received the event...
        val assessment = result.decision.assessment
        assertNotNull(assessment)
        assertEquals(RiskLevel.CONFIRMED, assessment?.riskLevel)
        assertEquals(DOMAIN, assessment?.event?.metadata?.let {
            it[com.vishal.riy.protection.event.ProtectionEvent.META_DOMAIN]
        })

        // §4-5 the policy engine decided the transition out of NORMAL...
        assertEquals(ProtectionState.RESTRICTED, result.decision.nextState)
        assertEquals(ProtectionDecision.REASON_CONTENT_DETECTED, result.decision.reason)
        assertEquals(NORMAL_TO_RESTRICTED, result.decision.duration)
    }

    @Test
    fun `the restricted-mode controller is invoked with the decided session`() {
        val result = processor.submit(adultDomainLookupEvent(DOMAIN, now))

        // §7 the controller received exactly the policy/session information the
        // decision produced, and it persisted it.
        val session = restricted.currentSession()
        assertNotNull(session)
        assertEquals(ProtectionState.RESTRICTED, session?.state)
        assertEquals(result.decision.reason, session?.reason)
        assertEquals(result.decision.policyVersion, session?.policyVersion)
        assertEquals(session, stateStore.current())
    }

    @Test
    fun `the enforcement layer is reached and reports applied`() {
        val result = processor.submit(adultDomainLookupEvent(DOMAIN, now))

        assertEquals(PolicyApplicationResult.APPLIED, result.enforcementResult)

        // The real allowlist was resolved from the (modelled) device, merged
        // with RIY, and read back verified — unchanged Phase 4 behaviour.
        val allowlist = dpm.rawLockTaskPackages().toSet()
        assertTrue(riyPackage in allowlist)
        assertTrue("the phone must remain allowed", "com.device.dialer" in allowlist)
        assertTrue("the keyboard must remain allowed", "com.device.ime" in allowlist)
        assertTrue("a declared wallet must remain allowed", "com.wallet.pay" in allowlist)
        assertEquals(1, dpm.setLockTaskPackagesCallCount)
    }

    // ------------------------------------------------------------- §8-9

    @Test
    fun `LockEngine remains the sole deadline authority`() {
        processor.submit(adultDomainLookupEvent(DOMAIN, now))

        val session = restricted.currentSession()!!
        val deadline = lockStore.loadState().lockEndEpochMillis

        // The session only MIRRORS the deadline LockEngine computed; the
        // processor never adds its own now + duration.
        assertEquals(
            "session expiry must equal the LockEngine deadline",
            deadline,
            session.expiryTime,
        )
        assertEquals(
            "and that deadline is exactly the existing 2-hour contract",
            now + LockEngine.LOCK_DURATION_MS,
            deadline,
        )
        assertTrue(LockEngine.isLocked(lockStore.loadState(), now))
    }

    @Test
    fun `repeated evidence during a live session does not create a second timer`() {
        processor.submit(adultDomainLookupEvent(DOMAIN, now))
        val firstSession = restricted.currentSession()!!
        val firstDeadline = lockStore.loadState().lockEndEpochMillis
        val nowAfter = now + 1_000L

        // A + AAAA + retries for the same domain arrive milliseconds later.
        now = nowAfter
        val repeated = processor.submit(adultDomainLookupEvent(DOMAIN, now))

        assertEquals("the session stays sticky at RESTRICTED",
            ProtectionState.RESTRICTED, repeated.decision.nextState)
        assertEquals(ProtectionDecision.REASON_NO_CHANGE, repeated.decision.reason)
        assertNull("no restriction was re-attempted", repeated.enforcementResult)

        // §9 no second (or extended) deadline, no second session, no second write.
        assertEquals(firstDeadline, lockStore.loadState().lockEndEpochMillis)
        assertEquals(firstSession, restricted.currentSession())
        assertEquals("the platform was written exactly once", 1, dpm.setLockTaskPackagesCallCount)
    }

    @Test
    fun `a different adult domain during a live session does not extend the deadline`() {
        processor.submit(adultDomainLookupEvent(DOMAIN, now))
        val firstDeadline = lockStore.loadState().lockEndEpochMillis

        now += 5_000L
        processor.submit(adultDomainLookupEvent("other.$DOMAIN", now))

        assertEquals(
            "the existing 2-hour window is not reset, shortened or extended",
            firstDeadline,
            lockStore.loadState().lockEndEpochMillis,
        )
        assertEquals(1, dpm.setLockTaskPackagesCallCount)
    }

    // ------------------------------------------------------- §12 stickiness

    @Test
    fun `an expired session lets a fresh event re-arm a new window`() {
        processor.submit(adultDomainLookupEvent(DOMAIN, now))
        val firstDeadline = lockStore.loadState().lockEndEpochMillis
        val firstSession = restricted.currentSession()!!

        // Time itself is the only thing that ends a restriction.
        now += LockEngine.LOCK_DURATION_MS + 1_000L
        val result = processor.submit(adultDomainLookupEvent(DOMAIN, now))

        assertEquals(ProtectionState.RESTRICTED, result.decision.nextState)
        assertNotEquals("a new session begins", firstSession.sessionId, restricted.currentSession()?.sessionId)
        assertTrue("the deadline moves forward with the new detection",
            lockStore.loadState().lockEndEpochMillis > firstDeadline)
        assertEquals(2, dpm.setLockTaskPackagesCallCount)
    }

    @Test
    fun `a session is not trusted as live without the LockEngine deadline`() {
        // A persisted session whose deadline is gone is not a restriction.
        stateStore.save(
            ProtectionSession(
                sessionId = "stale",
                state = ProtectionState.RESTRICTED,
                startTime = now,
                expiryTime = now + 7_200_000L,
                reason = ProtectionDecision.REASON_CONTENT_DETECTED,
                policyVersion = 1,
            ),
        )

        val result = processor.submit(adultDomainLookupEvent(DOMAIN, now))

        assertEquals(ProtectionState.RESTRICTED, result.decision.nextState)
        assertEquals(ProtectionDecision.REASON_CONTENT_DETECTED, result.decision.reason)
        assertNotEquals("the stale session was replaced", "stale", restricted.currentSession()?.sessionId)
    }

    // ------------------------------------------------------------ §10

    @Test
    fun `an enforcement failure is never reported as successful enforcement`() {
        buildProcessor()
        dpm.rejectSetLockTaskPackages = true

        val result = processor.submit(adultDomainLookupEvent(DOMAIN, now))

        assertNotEquals(PolicyApplicationResult.APPLIED, result.enforcementResult)
        assertEquals(PolicyApplicationResult.FAILED, result.enforcementResult)

        // Nothing was silently swallowed: the failure is in the event log under
        // the existing type vocabulary.
        val logged = eventStore.recent()
        assertTrue("the detection itself is always recorded",
            logged.any { it.type == ProtectionLogEvent.Type.CONTENT_DETECTED })
        assertFalse("no successful restriction may be claimed",
            logged.any { it.type == ProtectionLogEvent.Type.RESTRICTION_ENTERED })
        assertTrue(
            "the mismatch is exposed, not hidden",
            logged.any {
                it.type == ProtectionLogEvent.Type.INTEGRITY_MISMATCH &&
                    it.message.contains(PolicyApplicationResult.FAILED.name)
            },
        )
    }

    @Test
    fun `a non device owner is reported honestly and applies nothing`() {
        buildProcessor(owner = false)

        val result = processor.submit(adultDomainLookupEvent(DOMAIN, now))

        // The policy decision stays authoritative; the platform outcome is the
        // existing NOT_DEVICE_OWNER verdict — never a fake APPLIED.
        assertEquals(ProtectionState.RESTRICTED, result.decision.nextState)
        assertEquals(PolicyApplicationResult.NOT_DEVICE_OWNER, result.enforcementResult)
        assertTrue("no privileged write was attempted", dpm.rawLockTaskPackages().isEmpty())
    }

    // ------------------------------------------------------------- §logging

    @Test
    fun `the event log records evidence before the decision is acted on`() {
        processor.submit(adultDomainLookupEvent(DOMAIN, now))
        now += 1_000L
        // A second, duplicate event is still recorded as evidence even though the
        // policy requires no action.
        processor.submit(adultDomainLookupEvent(DOMAIN, now))

        val logged = eventStore.recent()
        assertEquals("every genuine observation is recorded", 3, logged.size)
        assertEquals(ProtectionLogEvent.Type.CONTENT_DETECTED, logged[0].type)
        assertEquals(ProtectionLogEvent.Type.RESTRICTION_ENTERED, logged[1].type)
        assertEquals(ProtectionLogEvent.Type.CONTENT_DETECTED, logged[2].type)
        assertTrue("no domain is duplicated into the log text",
            logged.none { DOMAIN in it.message })
    }

    // ------------------------------------------------------------ §recovery

    @Test
    fun `a persisted live session survives a process restart and stays authoritative`() {
        processor.submit(adultDomainLookupEvent(DOMAIN, now))
        val persisted = restricted.currentSession()!!

        // A controller built on the SAME stores is exactly a restarted process.
        val restarted = DefaultRestrictedModeController(enforcement, stateStore, lockStore) { now }

        assertEquals(persisted, restarted.recover())
        assertEquals(
            "the restriction is re-applied exactly as it was persisted",
            persisted,
            restarted.currentSession(),
        )
    }

    private companion object {
        const val NOW = 1_000_000L
        const val DOMAIN = "blocked.example"

        /** The existing policy's 2-hour duration, for the record only. */
        val NORMAL_TO_RESTRICTED = 2.toDuration(DurationUnit.HOURS)
    }
}
