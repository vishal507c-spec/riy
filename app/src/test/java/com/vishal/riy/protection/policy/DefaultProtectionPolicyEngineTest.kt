package com.vishal.riy.protection.policy

import com.vishal.riy.protection.event.ProtectionEvent
import com.vishal.riy.protection.event.ProtectionEventSource
import com.vishal.riy.protection.event.ProtectionEvidenceType
import com.vishal.riy.protection.risk.RiskAssessment
import com.vishal.riy.protection.risk.RiskLevel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.time.Duration
import kotlin.time.DurationUnit
import kotlin.time.toDuration

/**
 * The protection state machine: every legal transition, every forbidden one,
 * the exact 2-hour contract, policy versioning and determinism.
 *
 * These run on the plain JVM; if any Android framework symbol had leaked into
 * the policy package these tests could not exist.
 */
class DefaultProtectionPolicyEngineTest {

    private val policy = ProtectionPolicy()
    private val engine = DefaultProtectionPolicyEngine(policy)

    // ------------------------------------------------------------- helpers

    private fun assessment(level: RiskLevel, domain: String = "pornhub.com"): RiskAssessment =
        RiskAssessment(
            riskLevel = level,
            confidence = ProtectionEvent.Confidence.of(1.0),
            evidenceType = ProtectionEvidenceType.ADULT_DOMAIN_DNS_LOOKUP,
            reasoning = "matched $domain",
            event = ProtectionEvent(
                eventId = "ev-1",
                timestamp = 1_000_000L,
                source = ProtectionEventSource.DNS_FILTER,
                evidenceType = ProtectionEvidenceType.ADULT_DOMAIN_DNS_LOOKUP,
                confidence = ProtectionEvent.Confidence.of(1.0),
                metadata = mapOf(ProtectionEvent.META_DOMAIN to domain),
            ),
        )

    // -------------------------------------------------------- initial state

    @Test
    fun `NORMAL is the default state`() {
        val decision = engine.decide(assessment(RiskLevel.NORMAL), ProtectionState.NORMAL)
        assertEquals(ProtectionState.NORMAL, decision.nextState)
        assertEquals(ProtectionDecision.REASON_NO_CHANGE, decision.reason)
    }

    // --------------------------------------------------- two-hour contract

    @Test
    fun `restriction duration is exactly two hours`() {
        assertEquals(2.toDuration(DurationUnit.HOURS), policy.restrictionDuration)
    }

    @Test
    fun `CONFIRMED risk from NORMAL enters RESTRICTED for exactly 2 hours`() {
        val decision = engine.decide(assessment(RiskLevel.CONFIRMED), ProtectionState.NORMAL)

        assertEquals(ProtectionState.RESTRICTED, decision.nextState)
        assertEquals(2.toDuration(DurationUnit.HOURS), decision.duration)
        assertEquals(ProtectionDecision.REASON_CONTENT_DETECTED, decision.reason)
    }

    @Test
    fun `RESTRICTED risk level also maps to the 2-hour restriction`() {
        val decision = engine.decide(assessment(RiskLevel.RESTRICTED), ProtectionState.SUSPICIOUS)

        assertEquals(ProtectionState.RESTRICTED, decision.nextState)
        assertEquals(2.toDuration(DurationUnit.HOURS), decision.duration)
    }

    @Test
    fun `HARDENED is also time-boxed to the 2-hour duration`() {
        val decision = engine.decide(assessment(RiskLevel.HARDENED), ProtectionState.RESTRICTED)

        assertEquals(ProtectionState.HARDENED, decision.nextState)
        assertEquals(2.toDuration(DurationUnit.HOURS), decision.duration)
    }

    // ---------------------------------------------------- ascending edges

    @Test
    fun `NORMAL to SUSPICIOUS is legal`() {
        val decision = engine.decide(assessment(RiskLevel.SUSPICIOUS), ProtectionState.NORMAL)
        assertEquals(ProtectionState.SUSPICIOUS, decision.nextState)
    }

    @Test
    fun `SUSPICIOUS to CONFIRMED is legal`() {
        val decision = engine.decideTransition(
            currentState = ProtectionState.SUSPICIOUS,
            target = ProtectionState.CONFIRMED,
            reason = ProtectionDecision.REASON_CONTENT_DETECTED,
        )
        assertEquals(ProtectionState.CONFIRMED, decision.nextState)
        assertEquals(Duration.ZERO, decision.duration)
    }

    @Test
    fun `CONFIRMED to RESTRICTED is legal and armed for 2 hours`() {
        val decision = engine.decideTransition(
            currentState = ProtectionState.CONFIRMED,
            target = ProtectionState.RESTRICTED,
            reason = ProtectionDecision.REASON_CONTENT_DETECTED,
        )
        assertEquals(ProtectionState.RESTRICTED, decision.nextState)
        assertEquals(2.toDuration(DurationUnit.HOURS), decision.duration)
    }

    @Test
    fun `SUSPICIOUS to RESTRICTED is legal`() {
        val decision = engine.decide(assessment(RiskLevel.CONFIRMED), ProtectionState.SUSPICIOUS)
        assertEquals(ProtectionState.RESTRICTED, decision.nextState)
    }

    @Test
    fun `RESTRICTED to HARDENED is legal only via the HARDENED risk level`() {
        // The risk engine is what signals escalation; the policy engine only
        // allows it from an already-restricted session.
        val decision = engine.decide(assessment(RiskLevel.HARDENED), ProtectionState.RESTRICTED)
        assertEquals(ProtectionState.HARDENED, decision.nextState)
        assertEquals(ProtectionDecision.REASON_ESCALATED, decision.reason)
    }

    @Test
    fun `HARDENED risk from NORMAL does not jump straight to HARDENED`() {
        // No skipping the restriction: escalation must pass through RESTRICTED.
        val decision = engine.decide(assessment(RiskLevel.HARDENED), ProtectionState.NORMAL)
        assertEquals(ProtectionState.NORMAL, decision.nextState)
        assertEquals(ProtectionDecision.REASON_NO_CHANGE, decision.reason)
    }

    // ---------------------------------------------------- recovery edges

    @Test
    fun `RESTRICTED to RECOVERY is legal`() {
        val decision = engine.decideTransition(
            currentState = ProtectionState.RESTRICTED,
            target = ProtectionState.RECOVERY,
            reason = ProtectionDecision.REASON_BOOT_RECOVERY,
        )
        assertEquals(ProtectionState.RECOVERY, decision.nextState)
    }

    @Test
    fun `RECOVERY to NORMAL is legal`() {
        val decision = engine.decideTransition(
            currentState = ProtectionState.RECOVERY,
            target = ProtectionState.NORMAL,
            reason = ProtectionDecision.REASON_EXPIRED,
        )
        assertEquals(ProtectionState.NORMAL, decision.nextState)
    }

    @Test
    fun `RECOVERY to RESTRICTED restores the session`() {
        val decision = engine.decideTransition(
            currentState = ProtectionState.RECOVERY,
            target = ProtectionState.RESTRICTED,
            reason = ProtectionDecision.REASON_BOOT_RECOVERY,
        )
        assertEquals(ProtectionState.RESTRICTED, decision.nextState)
        assertEquals(2.toDuration(DurationUnit.HOURS), decision.duration)
    }

    @Test
    fun `HARDENED to RECOVERY is legal`() {
        val decision = engine.decideTransition(
            currentState = ProtectionState.HARDENED,
            target = ProtectionState.RECOVERY,
            reason = ProtectionDecision.REASON_BOOT_RECOVERY,
        )
        assertEquals(ProtectionState.RECOVERY, decision.nextState)
    }

    @Test
    fun `HARDENED to RESTRICTED de-escalates on expiry`() {
        val decision = engine.decideTransition(
            currentState = ProtectionState.HARDENED,
            target = ProtectionState.RESTRICTED,
            reason = ProtectionDecision.REASON_EXPIRED,
        )
        assertEquals(ProtectionState.RESTRICTED, decision.nextState)
    }

    // ------------------------------------------------------- stickiness

    @Test
    fun `a NORMAL event does not end a live RESTRICTED session`() {
        val decision = engine.decide(assessment(RiskLevel.NORMAL), ProtectionState.RESTRICTED)
        assertEquals(ProtectionState.RESTRICTED, decision.nextState)
        assertEquals(ProtectionDecision.REASON_NO_CHANGE, decision.reason)
    }

    @Test
    fun `a NORMAL event does not end a live HARDENED session`() {
        val decision = engine.decide(assessment(RiskLevel.NORMAL), ProtectionState.HARDENED)
        assertEquals(ProtectionState.HARDENED, decision.nextState)
    }

    @Test
    fun `SUSPICIOUS relaxes to NORMAL when the signal clears`() {
        val decision = engine.decide(assessment(RiskLevel.NORMAL), ProtectionState.SUSPICIOUS)
        assertEquals(ProtectionState.NORMAL, decision.nextState)
        assertEquals(ProtectionDecision.REASON_CLEARED, decision.reason)
    }

    @Test
    fun `CONFIRMED relaxes to NORMAL when the signal clears`() {
        val decision = engine.decide(assessment(RiskLevel.NORMAL), ProtectionState.CONFIRMED)
        assertEquals(ProtectionState.NORMAL, decision.nextState)
    }

    // ------------------------------------------------- forbidden jumps

    @Test
    fun `NORMAL to HARDENED is rejected`() {
        val decision = engine.decideTransition(
            currentState = ProtectionState.NORMAL,
            target = ProtectionState.HARDENED,
            reason = ProtectionDecision.REASON_ESCALATED,
        )
        assertEquals(ProtectionState.NORMAL, decision.nextState)
        assertEquals(ProtectionDecision.REASON_NO_CHANGE, decision.reason)
    }

    @Test
    fun `NORMAL to RECOVERY is rejected`() {
        val decision = engine.decideTransition(
            currentState = ProtectionState.NORMAL,
            target = ProtectionState.RECOVERY,
            reason = ProtectionDecision.REASON_BOOT_RECOVERY,
        )
        assertEquals(ProtectionState.NORMAL, decision.nextState)
    }

    @Test
    fun `RECOVERY to SUSPICIOUS is rejected`() {
        val decision = engine.decideTransition(
            currentState = ProtectionState.RECOVERY,
            target = ProtectionState.SUSPICIOUS,
            reason = ProtectionDecision.REASON_CONTENT_DETECTED,
        )
        assertEquals(ProtectionState.RECOVERY, decision.nextState)
    }

    @Test
    fun `SUSPICIOUS to HARDENED is rejected`() {
        val decision = engine.decide(assessment(RiskLevel.HARDENED), ProtectionState.SUSPICIOUS)
        assertEquals(ProtectionState.SUSPICIOUS, decision.nextState)
    }

    @Test
    fun `CONFIRMED to HARDENED is rejected`() {
        val decision = engine.decide(assessment(RiskLevel.HARDENED), ProtectionState.CONFIRMED)
        assertEquals(ProtectionState.CONFIRMED, decision.nextState)
    }

    @Test
    fun `REJECTED transitions never throw and never mutate`() {
        // Repeatedly asking for a forbidden jump is stable.
        repeat(5) {
            val decision = engine.decide(assessment(RiskLevel.HARDENED), ProtectionState.NORMAL)
            assertEquals(ProtectionState.NORMAL, decision.nextState)
        }
    }

    // --------------------------------------------------- policy version

    @Test
    fun `every decision carries the central policy version`() {
        val decisions = listOf(
            engine.decide(assessment(RiskLevel.CONFIRMED), ProtectionState.NORMAL),
            engine.decide(assessment(RiskLevel.NORMAL), ProtectionState.NORMAL),
            engine.decide(assessment(RiskLevel.HARDENED), ProtectionState.RESTRICTED),
            engine.decideTransition(ProtectionState.RESTRICTED, ProtectionState.RECOVERY, "r"),
        )
        decisions.forEach {
            assertEquals(policy.policyVersion, it.policyVersion)
        }
        assertEquals(ProtectionPolicy.CURRENT_POLICY_VERSION, policy.policyVersion)
    }

    @Test
    fun `a custom policy version is stamped onto decisions`() {
        val custom = DefaultProtectionPolicyEngine(ProtectionPolicy(policyVersion = 7))
        val decision = custom.decide(assessment(RiskLevel.CONFIRMED), ProtectionState.NORMAL)
        assertEquals(7, decision.policyVersion)
    }

    // ----------------------------------------------------- determinism

    @Test
    fun `identical inputs always produce an identical decision`() {
        val a = engine.decide(assessment(RiskLevel.CONFIRMED), ProtectionState.SUSPICIOUS)
        val b = engine.decide(assessment(RiskLevel.CONFIRMED), ProtectionState.SUSPICIOUS)
        assertEquals(a, b)
        assertEquals(a.hashCode(), b.hashCode())
    }

    @Test
    fun `different current states yield different outcomes for the same risk`() {
        val fromNormal = engine.decide(assessment(RiskLevel.HARDENED), ProtectionState.NORMAL)
        val fromRestricted = engine.decide(assessment(RiskLevel.HARDENED), ProtectionState.RESTRICTED)
        assertNotEquals(fromNormal.nextState, fromRestricted.nextState)
    }

    // ------------------------------------------------- risk → state map

    @Test
    fun `risk to state mapping is exhaustive and deterministic`() {
        // Each level is evaluated from a start state that makes the resulting
        // transition legal, so the mapping itself is what is verified.
        val cases = listOf(
            Triple(RiskLevel.NORMAL, ProtectionState.SUSPICIOUS, ProtectionState.NORMAL),
            Triple(RiskLevel.SUSPICIOUS, ProtectionState.NORMAL, ProtectionState.SUSPICIOUS),
            Triple(RiskLevel.CONFIRMED, ProtectionState.NORMAL, ProtectionState.RESTRICTED),
            Triple(RiskLevel.RESTRICTED, ProtectionState.SUSPICIOUS, ProtectionState.RESTRICTED),
            Triple(RiskLevel.HARDENED, ProtectionState.RESTRICTED, ProtectionState.HARDENED),
        )
        cases.forEach { (level, from, expected) ->
            val decision = engine.decide(assessment(level), from)
            assertEquals("risk $level from $from", expected, decision.nextState)
        }
    }

    @Test
    fun `a risk level with no legal edge from the current state is rejected`() {
        // HARDENED risk while SUSPICIOUS cannot skip the restriction.
        assertEquals(
            ProtectionState.SUSPICIOUS,
            engine.decide(assessment(RiskLevel.HARDENED), ProtectionState.SUSPICIOUS).nextState,
        )
    }

    @Test
    fun `a risk assessment cannot bypass the transition table`() {
        // HARDENED risk while CONFIRMED must not jump to HARDENED.
        val decision = engine.decide(assessment(RiskLevel.HARDENED), ProtectionState.CONFIRMED)
        assertTrue(decision.nextState == ProtectionState.CONFIRMED)
    }

    // ------------------------------------------------ no Android leak

    @Test
    fun `policy engine is constructible and usable on the plain JVM`() {
        // This test compiling and running is itself the check: the policy
        // package references no Android framework class.
        val engine = DefaultProtectionPolicyEngine()
        assertEquals(ProtectionState.NORMAL, engine.decide(assessment(RiskLevel.NORMAL), ProtectionState.NORMAL).nextState)
    }
}
