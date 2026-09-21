package com.vishal.riy.protection.policy

import com.vishal.riy.protection.risk.RiskAssessment

/**
 * THE protection authority. Given the latest risk assessment and the current
 * protection state, it decides — and only decides — the next state.
 *
 * HARD CONTRACT — an implementation must:
 *  - remain pure Kotlin (no `android.*` imports);
 *  - be deterministic given the same inputs;
 *  - be the ONLY component that transitions [ProtectionState];
 *  - touch NO DevicePolicyManager, NO UI, NO lock task, NO storage.
 *
 * Nothing else in the app (Activity, Composable, service, controller) is
 * permitted to compute or force a state transition. This is what keeps a UI
 * bug or a tampered screen from weakening protection.
 *
 * Phase 2 establishes the contract only; real transition rules arrive later.
 */
fun interface ProtectionPolicyEngine {

    /**
     * @param assessment    the latest classified evidence (may represent a
     *                      no-signal event).
     * @param currentState  the state the device is currently in.
     * @return the decision to apply. Returning a decision with
     *         [ProtectionDecision.nextState] == [currentState] and reason
     *         [ProtectionDecision.REASON_NO_CHANGE] is the explicit "do
     *         nothing" answer.
     */
    fun decide(assessment: RiskAssessment, currentState: ProtectionState): ProtectionDecision
}
