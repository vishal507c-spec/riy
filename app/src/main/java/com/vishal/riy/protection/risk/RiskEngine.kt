package com.vishal.riy.protection.risk

import com.vishal.riy.protection.event.ProtectionEvent

/**
 * Classifies a [ProtectionEvent] into a [RiskAssessment].
 *
 * HARD CONTRACT — an implementation of this interface must:
 *  - remain pure Kotlin (no `android.*` imports at all);
 *  - be deterministic given the same event;
 *  - touch NO Android API, NO DevicePolicyManager, NO UI, NO storage;
 *  - start, stop or observe NO lock task;
 *  - persist NOTHING.
 *
 * It may not decide the protection state — it only grades evidence. All state
 * transitions live in [com.vishal.riy.protection.policy.ProtectionPolicyEngine].
 *
 * Phase 2 establishes the contract only; a real implementation arrives later.
 */
fun interface RiskEngine {

    /**
     * Maps one observed event to its severity assessment.
     *
     * @param event the evidence; never null.
     * @return the assessment; never null (an event with no signal returns
     *         [RiskLevel.NORMAL] rather than an absence).
     */
    fun assess(event: ProtectionEvent): RiskAssessment
}
