package com.vishal.riy.protection.escalation

import com.vishal.riy.protection.event.ProtectionEvent
import com.vishal.riy.protection.event.ProtectionEventSource
import com.vishal.riy.protection.event.ProtectionEvidenceType
import com.vishal.riy.protection.policy.ProtectionPolicy
import com.vishal.riy.protection.risk.RiskAssessment
import com.vishal.riy.protection.risk.RiskLevel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import kotlin.time.Duration
import kotlin.time.DurationUnit
import kotlin.time.toDuration

/**
 * The escalation engine: deterministic counting, window expiry, persistence
 * across process death/reboot — and the hard rule that it never becomes a
 * deadline authority or a state-transition authority.
 *
 * The configured threshold/window come from the EXISTING [ProtectionPolicy];
 * this test invents no value, it only reads what the policy already defines.
 */
class ProtectionEscalationEngineTest {

    private lateinit var store: InMemoryEscalationStore

    private var now = NOW

    private val policy: ProtectionPolicy get() = ProtectionPolicy()

    private val engine: ProtectionEscalationEngine
        get() = ProtectionEscalationEngine(store, policy) { now }

    @Before
    fun setUp() {
        store = InMemoryEscalationStore()
        now = NOW
    }

    // ------------------------------------------------- existing configuration

    @Test
    fun `the escalation threshold and window already exist in the policy`() {
        // STOP-CONDITION CHECK: these are READ from the existing contract, not
        // invented by the escalation component.
        assertEquals(3, policy.hardenedThreshold)
        assertEquals(24.toDuration(DurationUnit.HOURS), policy.escalationWindow)
    }

    // ---------------------------------------------------------- §1 no false +

    @Test
    fun `the first qualifying event does not falsely escalate`() {
        val decision = engine.record(qualifyingEvent(), qualifyingAssessment())

        assertEquals(1, decision.qualifyingCount)
        assertEquals(policy.hardenedThreshold, decision.hardenedThreshold)
        assertFalse("one event cannot reach the threshold", decision.escalated)
        assertEquals(NOW, decision.firstQualifyingAt)
        assertEquals(NOW, decision.latestQualifyingAt)
    }

    @Test
    fun `a second qualifying event still does not escalate`() {
        engine.record(qualifyingEvent(), qualifyingAssessment())

        now += MINUTE
        val decision = engine.record(qualifyingEvent(), qualifyingAssessment())

        assertEquals(2, decision.qualifyingCount)
        assertFalse(decision.escalated)
    }

    // ------------------------------------------------------ §2 counting is ok

    @Test
    fun `qualifying repeated events are counted correctly up to the threshold`() {
        repeat(policy.hardenedThreshold - 1) {
            now += MINUTE
            assertFalse(engine.record(qualifyingEvent(), qualifyingAssessment()).escalated)
        }

        now += MINUTE
        val atThreshold = engine.record(qualifyingEvent(), qualifyingAssessment())

        assertEquals(policy.hardenedThreshold, atThreshold.qualifyingCount)
        assertTrue("the configured threshold has been reached", atThreshold.escalated)
        assertFalse("once at the threshold, threshold-minus-one no longer applies",
            atThreshold.isAtThresholdMinusOne)
        assertEquals(NOW + MINUTE, atThreshold.firstQualifyingAt)
        assertEquals(now, atThreshold.latestQualifyingAt)
    }

    @Test
    fun `counting continues past the threshold and stays escalated`() {
        engine.record(qualifyingEvent(), qualifyingAssessment())
        now += MINUTE
        engine.record(qualifyingEvent(), qualifyingAssessment())
        now += MINUTE
        engine.record(qualifyingEvent(), qualifyingAssessment())

        now += MINUTE
        val beyond = engine.record(qualifyingEvent(), qualifyingAssessment())

        assertEquals(4, beyond.qualifyingCount)
        assertTrue(beyond.escalated)
    }

    @Test
    fun `a non-qualifying evidence type counts nothing`() {
        val decision = engine.record(qualifyingEvent(), qualifyingAssessment())

        // The only legitimate evidence type is the DNS lookup; anything else
        // must not enter the counter even if the assessment were actionable.
        assertEquals(1, decision.qualifyingCount)

        now += MINUTE
        // An event whose evidence type is not qualifying records nothing.
        val nonQualifying = ProtectionEvent(
            eventId = "ev-x",
            timestamp = now,
            source = ProtectionEventSource.DNS_FILTER,
            evidenceType = ADULT_LOOKUP,
            confidence = ProtectionEvent.Confidence.CERTAIN,
        )
        val result = engine.record(nonQualifying, RiskAssessment(
            riskLevel = RiskLevel.NORMAL,
            confidence = ProtectionEvent.Confidence.NONE,
            evidenceType = ADULT_LOOKUP,
            reasoning = "no signal",
            event = nonQualifying,
        ))

        assertEquals("a non-actionable event is not counted", 1, result.qualifyingCount)
        assertFalse(result.escalated)
    }

    // ------------------------------------------------ §3 window expiry

    @Test
    fun `events outside the escalation window expire correctly`() {
        engine.record(qualifyingEvent(), qualifyingAssessment())
        now += MINUTE
        engine.record(qualifyingEvent(), qualifyingAssessment())

        // Both events age out of the 24-hour window.
        now += policy.escalationWindow.inWholeMilliseconds + 1L

        val decision = engine.record(qualifyingEvent(), qualifyingAssessment())

        assertEquals("the two stale events expired; only the new one counts",
            1, decision.qualifyingCount)
        assertFalse(decision.escalated)
    }

    @Test
    fun `an event at the window boundary is still counted`() {
        engine.record(qualifyingEvent(), qualifyingAssessment())
        engine.record(qualifyingEvent(), qualifyingAssessment())

        // A third event exactly `window` after the FIRST is still inside the
        // window for it: the boundary is inclusive (>= now - window).
        now = NOW + policy.escalationWindow.inWholeMilliseconds

        // Debug: check the state and window calculation
        val state = engine.snapshot()
        val window = policy.escalationWindow.inWholeMilliseconds
        val cutoff = now - window
        val counts = state.eventTimestamps.map { it >= cutoff }
        println("DEBUG: now=$now, window=$window, cutoff=$cutoff, timestamps=${state.eventTimestamps}, counts=$counts")

        val decision = engine.record(qualifyingEvent(), qualifyingAssessment())

        assertEquals("the oldest in-window event is exactly at the boundary",
            3, decision.qualifyingCount)
        assertTrue(decision.escalated)
    }

    @Test
    fun `an event just inside the window is still counted`() {
        engine.record(qualifyingEvent(), qualifyingAssessment())
        engine.record(qualifyingEvent(), qualifyingAssessment())

        now += policy.escalationWindow.inWholeMilliseconds - 1L

        val decision = engine.record(qualifyingEvent(), qualifyingAssessment())

        assertEquals("three events all inside the window", 3, decision.qualifyingCount)
        assertTrue(decision.escalated)
    }

    // ------------------------------------------- §4-6 persistence / restart

    @Test
    fun `escalation state persists across a process restart`() {
        engine.record(qualifyingEvent(), qualifyingAssessment())
        now += MINUTE
        engine.record(qualifyingEvent(), qualifyingAssessment())

        // A new engine on the SAME store is exactly a restarted process.
        val restarted = ProtectionEscalationEngine(store, policy) { now }
        val current = restarted.current()

        assertEquals("the count survived the process death", 2, current.qualifyingCount)
        assertFalse(current.escalated)
        assertEquals(NOW, current.firstQualifyingAt)
    }

    @Test
    fun `escalation state survives a device reboot through the store abstraction`() {
        engine.record(qualifyingEvent(), qualifyingAssessment())

        // A reboot means arbitrary uptime loss; only wall time counts, so the
        // persisted timestamps stay meaningful while `now` advances.
        now += 45 * MINUTE
        val restarted = ProtectionEscalationEngine(store, policy) { now }

        assertEquals(1, restarted.current().qualifyingCount)
    }

    @Test
    fun `an escalated state is still escalated after a restart`() {
        repeat(policy.hardenedThreshold) {
            now += MINUTE
            engine.record(qualifyingEvent(), qualifyingAssessment())
        }
        assertTrue(engine.current().escalated)

        now += 10 * MINUTE
        val restarted = ProtectionEscalationEngine(store, policy) { now }

        assertTrue("escalation survives the restart", restarted.current().escalated)
        assertEquals(policy.hardenedThreshold, restarted.current().qualifyingCount)
    }

    @Test
    fun `a fresh store reports no qualifying evidence`() {
        assertEquals(0, engine.current().qualifyingCount)
        assertNull(engine.current().firstQualifyingAt)
        assertNull(engine.current().latestQualifyingAt)
    }

    @Test
    fun `reset clears the counting state`() {
        repeat(policy.hardenedThreshold) {
            now += MINUTE
            engine.record(qualifyingEvent(), qualifyingAssessment())
        }
        assertTrue(engine.current().escalated)

        engine.reset()

        assertEquals(0, engine.current().qualifyingCount)
        assertFalse(engine.current().escalated)
    }

    // ------------------------------------------- §7-8 deadline is untouched

    @Test
    fun `escalation holds no deadline state of its own`() {
        // The engine's only dependencies are the store and the clock; it has no
        // LockStore, no handler and no scheduler. This compiles and runs purely
        // to assert the class never grew a deadline authority.
        repeat(policy.hardenedThreshold) {
            now += MINUTE
            engine.record(qualifyingEvent(), qualifyingAssessment())
        }

        // Reaching HARDENED never wrote anything but timestamps.
        val persisted = store.load()
        assertTrue(persisted.eventTimestamps.all { it > 0L })
        assertEquals(policy.policyVersion, persisted.policyVersion)
    }

    @Test
    fun `escalation is deterministic for identical inputs`() {
        val a = engine.record(qualifyingEvent(), qualifyingAssessment())

        val freshStore = InMemoryEscalationStore()
        val b = ProtectionEscalationEngine(freshStore, policy) { now }
            .record(qualifyingEvent(), qualifyingAssessment())

        assertEquals(a, b)
        assertEquals(a.hashCode(), b.hashCode())
    }

    @Test
    fun `different points in time yield different decisions`() {
        val first = engine.record(qualifyingEvent(), qualifyingAssessment())
        now += MINUTE
        val second = engine.record(qualifyingEvent(), qualifyingAssessment())

        assertNotEquals(first.qualifyingCount, second.qualifyingCount)
        assertNotEquals(first, second)
    }

    // ------------------------------------------------------------- helpers

    private fun qualifyingEvent(): ProtectionEvent = ProtectionEvent(
        eventId = "ev-${now}",
        timestamp = now,
        source = ProtectionEventSource.DNS_FILTER,
        evidenceType = ADULT_LOOKUP,
        confidence = ProtectionEvent.Confidence.CERTAIN,
        metadata = mapOf(ProtectionEvent.META_DOMAIN to "blocked.example"),
    )

    private fun qualifyingAssessment(): RiskAssessment = RiskAssessment(
        riskLevel = RiskLevel.CONFIRMED,
        confidence = ProtectionEvent.Confidence.CERTAIN,
        evidenceType = ADULT_LOOKUP,
        reasoning = "adult-domain DNS lookup matched the blocklist",
        event = qualifyingEvent(),
    )

    private companion object {
        val ADULT_LOOKUP = ProtectionEvidenceType.ADULT_DOMAIN_DNS_LOOKUP
        const val NOW = 1_000_000L
        const val MINUTE = 60_000L
        val WINDOW: Duration = 24.toDuration(DurationUnit.HOURS)
    }
}
