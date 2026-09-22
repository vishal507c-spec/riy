package com.vishal.riy.protection.escalation

import com.vishal.riy.protection.event.ProtectionEvent
import com.vishal.riy.protection.event.ProtectionEvidenceType
import com.vishal.riy.protection.policy.ProtectionPolicy
import com.vishal.riy.protection.policy.ProtectionState
import com.vishal.riy.protection.risk.RiskAssessment

/**
 * THE stateful escalation component. It owns exactly ONE thing: turning a
 * sequence of qualifying protection events into an escalation decision.
 *
 * It is deliberately SEPARATE from the stateless
 * [com.vishal.riy.protection.risk.RiskEngine] (Phase 5, FROZEN): that engine
 * grades ONE observation and is correct to stay stateless, because a single
 * DNS lookup carries no notion of "how many times". Escalation is inherently a
 * function across events, which is a different responsibility and therefore a
 * different owner.
 *
 * WHAT THIS ENGINE OWNS — and nothing else:
 *  - counting qualifying protection events;
 *  - evaluating the existing [ProtectionPolicy.escalationWindow];
 *  - determining whether escalation is warranted;
 *  - producing the escalation metadata/decision.
 *
 * WHAT IT MUST NOT DO (enforced by construction — see the injected store and
 * clock, which are its only dependencies):
 *  - it performs NO DNS detection;
 *  - it performs NO risk classification (it CONSUMES the risk engine's
 *    assessment; it never grades an event itself);
 *  - it transitions NO [ProtectionState] — it only DESCRIBES the escalation
 *    the policy engine may then accept. The policy engine remains the ONLY
 *    state-transition authority;
 *  - it computes NO deadline and starts NO timer. HARDENED reuses the existing
 *    2-hour deadline owned by [com.vishal.riy.lock.LockEngine]; a repeated
 *    event during a live session must not re-arm, extend or shorten it;
 *  - it touches NO DevicePolicyManager, NO UI and NO restricted lifecycle.
 *
 * DETERMINISM. Given the same persisted [EscalationState], the same event and
 * the same clock value, [record] always yields the same result. The store is
 * the only carrier of state across calls, so a fresh engine reading the same
 * store reproduces the outcome exactly — which is what makes process death and
 * reboot testable without a device.
 */
class ProtectionEscalationEngine(

    private val store: ProtectionEscalationStore,

    private val policy: ProtectionPolicy = ProtectionPolicy(),

    private val clock: () -> Long = System::currentTimeMillis,

) {

    /**
     * Records one [event] and decides whether the accumulated qualifying
     * evidence now warrants escalation.
     *
     * @param assessment the risk engine's classification of the same event.
     *     Only an actionable assessment makes an event qualifying — a
     *     [com.vishal.riy.protection.risk.RiskLevel.NORMAL] event records
     *     nothing and escalates nothing.
     * @return the decision, never null. See [EscalationDecision].
     */
    fun record(event: ProtectionEvent, assessment: RiskAssessment): EscalationDecision {
        val now = clock()

        // Only a genuine adult-domain observation counts. The check is the
        // closed enum's own predicate, so the escalation threshold can never be
        // reached by a fabricated or unrelated signal, and the two adult-domain
        // tiers (a direct blocklist match and a corroborated ambiguous signal)
        // both count once each — never double for one episode, because the
        // intelligence layer produces exactly one event per correlated episode.
        val qualifies = event.evidenceType.isAdultDomainEvidence &&
            assessment.isActionable

        if (!qualifies) {
            // Nothing to count; the existing state is reported unchanged.
            return decisionFor(store.load(), now, escalated = false)
        }

        // The evidence's OWN timestamp is authoritative: it is the moment the
        // observation happened, while [clock] may be later (queued/persisted
        // events). Using the event timestamp is what keeps the window honest.
        val recorded = store.load()
        val withThis = recorded.eventTimestamps + event.timestamp
        val state = EscalationState(
            eventTimestamps = withThis,
            policyVersion = policy.policyVersion,
        )
        store.save(state)

        return decisionFor(
            state,
            now,
            escalated = qualifyingCount(state, now) >= policy.hardenedThreshold,
        )
    }

    /** Current persisted state for diagnostics/testing. */
    fun snapshot(): EscalationState = store.load()

    /**
     * The decision for the currently persisted state, without recording a new
     * event. This is the read a recovery pass performs: it MUST see the same
     * escalation verdict a restarted process would see.
     */
    fun current(): EscalationDecision =
        decisionFor(store.load(), clock(), escalated = false).let { current ->
            if (current.qualifyingCount >= policy.hardenedThreshold) current.copy(escalated = true)
            else current
        }

    /**
     * Drops the persisted counting state. Called only when the policy engine's
     * own paths have concluded the protection session — never by this engine
     * on its own initiative, and never to weaken a live restriction.
     */
    fun reset() {
        store.clear()
    }

    // --------------------------------------------------------------- internals

    private fun decisionFor(
        state: EscalationState,
        now: Long,
        escalated: Boolean,
    ): EscalationDecision {
        val inWindow = state.eventTimestamps.filter { it >= now - windowMillis() }
        val count = inWindow.size
        return EscalationDecision(
            qualifyingCount = count,
            hardenedThreshold = policy.hardenedThreshold,
            escalated = escalated && count >= policy.hardenedThreshold,
            firstQualifyingAt = inWindow.minOrNull(),
            latestQualifyingAt = inWindow.maxOrNull(),
            policyVersion = state.policyVersion,
        )
    }

    /**
     * How many qualifying events fall inside the window right now. Events
     * outside the window expire here, at READ time, rather than being deleted
     * from persistence — the store stays a faithful record while the decision
     * only ever counts recent evidence.
     */
    private fun qualifyingCount(state: EscalationState, now: Long): Int =
        state.eventTimestamps.count { it >= now - windowMillis() }

    private fun windowMillis(): Long = policy.escalationWindow.inWholeMilliseconds
}

/**
 * The escalation component's only output: a description of the accumulated
 * qualifying evidence and whether it warrants escalation. Pure Kotlin; it can
 * execute nothing.
 *
 * This is ADVICE, not authority. [EscalationDecision.escalated] tells the
 * pipeline that the threshold has been reached; the actual
 * [ProtectionState] transition remains the exclusive responsibility of
 * [com.vishal.riy.protection.policy.ProtectionPolicyEngine], which validates
 * RESTRICTED → HARDENED against its own legal-transition table.
 *
 * @param qualifyingCount   qualifying events inside the escalation window.
 * @param hardenedThreshold the configured
 *                          [ProtectionPolicy.hardenedThreshold].
 * @param escalated         true only when [qualifyingCount] has reached
 *                          [hardenedThreshold].
 * @param firstQualifyingAt epoch-ms of the oldest in-window event, or null.
 * @param latestQualifyingAt epoch-ms of the newest in-window event, or null.
 * @param policyVersion     the policy that produced this decision.
 */
data class EscalationDecision(

    val qualifyingCount: Int,

    val hardenedThreshold: Int,

    val escalated: Boolean,

    val firstQualifyingAt: Long?,

    val latestQualifyingAt: Long?,

    val policyVersion: Int,

) {

    /** True when one more qualifying event would reach the threshold. */
    val isAtThresholdMinusOne: Boolean
        get() = qualifyingCount == hardenedThreshold - 1 && hardenedThreshold > 1
}
