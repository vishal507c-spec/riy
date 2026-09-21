package com.vishal.riy.protection.policy

import com.vishal.riy.protection.risk.RiskAssessment
import com.vishal.riy.protection.risk.RiskLevel
import kotlin.time.Duration

/**
 * The deterministic, pure-Kotlin implementation of RIY's protection state
 * machine. It is the ONLY component that decides protection-state transitions.
 *
 * What this class deliberately does NOT do:
 *  - it never touches DevicePolicyManager, PackageManager, SharedPreferences,
 *    the VPN service, the UI, or the lock engine;
 *  - it never persists anything;
 *  - it never computes a competing expiry deadline. It returns only the
 *    *duration* of a restriction (2 hours); the actual wall-clock deadline
 *    stays owned by the existing `com.vishal.riy.lock.LockEngine`, which this
 *    engine never calls.
 *
 * Determinism: [decide] is a pure function of
 * (`current state`, `risk assessment`, [policy]). The same inputs always yield
 * the same [ProtectionDecision]; there is no internal mutable state and no
 * singleton.
 *
 * Risk → state mapping (see [mapRiskToState]) is combined with transition
 * validation: a risk assessment can never force an illegal jump. When a
 * requested transition is not permitted, the engine rejects it deterministically
 * by returning a decision that keeps [currentState] with reason
 * [ProtectionDecision.REASON_NO_CHANGE] — it never throws and never mutates
 * anything.
 *
 * HARDENED escalation: the risk engine signals escalation by producing
 * [RiskLevel.HARDENED]; this engine then validates the RESTRICTED → HARDENED
 * transition against the same table. The escalation rule itself lives in the
 * risk engine, not here, and not in any UI.
 */
class DefaultProtectionPolicyEngine(

    private val policy: ProtectionPolicy = ProtectionPolicy(),

) : ProtectionPolicyEngine {

    /**
     * The complete, explicit set of legal state transitions. Any pair not
     * present here is rejected. This table is the single statement of the state
     * machine's shape.
     */
    private val legalTransitions: Map<ProtectionState, Set<ProtectionState>> = mapOf(
        ProtectionState.NORMAL to setOf(
            ProtectionState.SUSPICIOUS,
            ProtectionState.CONFIRMED,
            ProtectionState.RESTRICTED,
        ),
        ProtectionState.SUSPICIOUS to setOf(
            ProtectionState.CONFIRMED,
            ProtectionState.RESTRICTED,
            ProtectionState.NORMAL,
        ),
        ProtectionState.CONFIRMED to setOf(
            ProtectionState.RESTRICTED,
            ProtectionState.NORMAL,
        ),
        ProtectionState.RESTRICTED to setOf(
            ProtectionState.HARDENED,
            ProtectionState.RECOVERY,
            ProtectionState.NORMAL,
        ),
        ProtectionState.HARDENED to setOf(
            ProtectionState.RECOVERY,
            ProtectionState.RESTRICTED,
        ),
        ProtectionState.RECOVERY to setOf(
            ProtectionState.NORMAL,
            ProtectionState.RESTRICTED,
        ),
    )

    /** The active policy version, stamped onto every decision. */
    val policyVersion: Int get() = policy.policyVersion

    override fun decide(
        assessment: RiskAssessment,
        currentState: ProtectionState,
    ): ProtectionDecision {

        val requested = mapRiskToState(assessment.riskLevel)

        // 1. Session stickiness: a live RESTRICTED/HARDENED session is ended
        //    ONLY by the policy engine's own expiry/recovery paths (see
        //    [decideTransition]), never by a low-risk event arriving later.
        if (currentState.isSessionActive && severity(requested) < severity(currentState)) {
            return noChange(currentState, assessment)
        }

        // 2. The risk level already matches the current state: nothing to do.
        if (requested == currentState) return noChange(currentState, assessment)

        // 3. A legal advancing transition, as mapped from the risk level. This
        //    also covers the relaxation edges (SUSPICIOUS/CONFIRMED → NORMAL)
        //    that the risk mapping requests when the signal clears.
        if (isLegal(currentState, requested)) {
            return ProtectionDecision(
                nextState = requested,
                reason = reasonFor(currentState, requested),
                duration = durationFor(requested),
                policyVersion = policy.policyVersion,
                assessment = assessment,
            )
        }

        return noChange(currentState, assessment)
    }

    /**
     * Explicit, assessment-free transitions used by infrastructure paths:
     * expiry (RESTRICTED → NORMAL), recovery (→ RECOVERY), and recovery
     * resolution (RECOVERY → NORMAL / RESTRICTED). These are the ONLY way to
     * reach those edges, and each is still validated against the same table.
     */
    fun decideTransition(
        currentState: ProtectionState,
        target: ProtectionState,
        reason: String,
    ): ProtectionDecision {
        if (!isLegal(currentState, target)) return noChange(currentState, null)
        return ProtectionDecision(
            nextState = target,
            reason = reason,
            duration = durationFor(target),
            policyVersion = policy.policyVersion,
            assessment = null,
        )
    }

    /** True only when [state] runs an active time-boxed session. */
    private val ProtectionState.isSessionActive: Boolean
        get() = this == ProtectionState.RESTRICTED || this == ProtectionState.HARDENED

    /**
     * Severity for the stickiness comparison. Deliberately explicit rather than
     * enum-ordinal based, because RECOVERY is a transitional state and must not
     * rank as "more severe" than RESTRICTED or HARDENED.
     */
    private fun severity(state: ProtectionState): Int = when (state) {
        ProtectionState.NORMAL -> 0
        ProtectionState.SUSPICIOUS -> 1
        ProtectionState.CONFIRMED -> 2
        ProtectionState.RESTRICTED -> 3
        ProtectionState.HARDENED -> 4
        ProtectionState.RECOVERY -> 3
    }

    /** Deterministic risk-level → protection-state mapping. */
    private fun mapRiskToState(level: RiskLevel): ProtectionState = when (level) {
        RiskLevel.NORMAL -> ProtectionState.NORMAL
        RiskLevel.SUSPICIOUS -> ProtectionState.SUSPICIOUS
        RiskLevel.CONFIRMED -> ProtectionState.RESTRICTED
        RiskLevel.RESTRICTED -> ProtectionState.RESTRICTED
        RiskLevel.HARDENED -> ProtectionState.HARDENED
    }

    private fun isLegal(from: ProtectionState, to: ProtectionState): Boolean =
        legalTransitions[from]?.contains(to) == true

    /** Exactly the policy's restriction duration for session states, else zero. */
    private fun durationFor(state: ProtectionState): Duration = when (state) {
        ProtectionState.RESTRICTED, ProtectionState.HARDENED -> policy.restrictionDuration
        else -> Duration.ZERO
    }

    private fun reasonFor(
        from: ProtectionState,
        to: ProtectionState,
    ): String = when (to) {
        ProtectionState.HARDENED -> ProtectionDecision.REASON_ESCALATED
        ProtectionState.NORMAL -> when (from) {
            ProtectionState.RECOVERY, ProtectionState.RESTRICTED, ProtectionState.HARDENED ->
                ProtectionDecision.REASON_EXPIRED
            else -> ProtectionDecision.REASON_CLEARED
        }
        ProtectionState.RESTRICTED -> when (from) {
            ProtectionState.RECOVERY,
            ProtectionState.HARDENED,
            -> ProtectionDecision.REASON_BOOT_RECOVERY
            else -> ProtectionDecision.REASON_CONTENT_DETECTED
        }
        ProtectionState.SUSPICIOUS -> ProtectionDecision.REASON_CONTENT_DETECTED
        else -> ProtectionDecision.REASON_NO_CHANGE
    }

    private fun noChange(
        currentState: ProtectionState,
        assessment: RiskAssessment?,
    ): ProtectionDecision = ProtectionDecision(
        nextState = currentState,
        reason = ProtectionDecision.REASON_NO_CHANGE,
        duration = Duration.ZERO,
        policyVersion = policy.policyVersion,
        assessment = assessment,
    )
}
