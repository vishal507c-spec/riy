package com.vishal.riy.protection.policy

import com.vishal.riy.protection.risk.RiskAssessment
import kotlin.time.Duration

/**
 * The one and only output of the policy engine: an authoritative instruction
 * about what protection state the device must be in next, and why.
 *
 * Pure Kotlin. Carries no Android reference and cannot execute anything. The
 * enforcement layer is what later acts on [nextState].
 *
 * @param nextState    the state the device must transition to.
 * @param reason       a short stable code/phrase for logs and the UI.
 * @param duration     how long the new state lasts (relevant for RESTRICTED and
 *                     HARDENED; [Duration.ZERO] for NORMAL).
 * @param policyVersion the policy version that produced this decision.
 * @param assessment   the evidence that triggered it, if any (absent for
 *                     time-driven transitions such as expiry).
 */
data class ProtectionDecision(

    val nextState: ProtectionState,

    val reason: String,

    val duration: Duration = Duration.ZERO,

    val policyVersion: Int,

    val assessment: RiskAssessment? = null,
) {

    /** True when the decision asks the device to enter a restricted session. */
    val entersRestriction: Boolean
        get() = nextState == ProtectionState.RESTRICTED || nextState == ProtectionState.HARDENED

    companion object {

        /** Reason code used when a session expires and normal policy returns. */
        const val REASON_EXPIRED = "restriction_expired"

        /** Reason code used when prohibited content is detected. */
        const val REASON_CONTENT_DETECTED = "content_detected"

        /** Reason code used when repeated detections escalate the session. */
        const val REASON_ESCALATED = "repeated_detections"

        /** Reason code used when boot recovery restores a live session. */
        const val REASON_BOOT_RECOVERY = "boot_recovery"

        /** Reason code used when a non-restricted state clears (no further signal). */
        const val REASON_CLEARED = "no_further_signal"

        /** Reason code used when no transition is required. */
        const val REASON_NO_CHANGE = "no_change"
    }
}
