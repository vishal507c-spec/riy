package com.vishal.riy.protection.enforcement

import com.vishal.riy.protection.state.ProtectionSession

/**
 * The ONLY component permitted to touch Android's DevicePolicyManager /
 * lock-task machinery. Everything above it in the pipeline (risk, policy,
 * state, restricted-mode controller) stays pure Kotlin and platform-free, so
 * this interface is the seam where a testable core meets the platform.
 *
 * HARD CONTRACT — an implementation must:
 *  - apply exactly what the policy engine decided, never more, never less;
 *  - expose NO method that weakens or disables protection
 *    (no disable/pause/bypass/unlock/remove-restriction API exists here);
 *  - keep [restoreNormalPolicy] usable ONLY as the counterpart of an expired
 *    or recovered session, driven by the policy engine's decision.
 *
 * Status of the currently applied platform policy, for the integrity engine
 * and the UI.
 */
enum class EnforcementStatus {

    /** A restrictive lock-task policy is currently applied. */
    ACTIVE_RESTRICTED,

    /** Normal (unrestricted) device policy is in force. */
    NORMAL,

    /** The engine is mid-reconciliation after a mismatch. */
    RECONCILING,
}

/**
 * Phase 2 establishes the contract only. NO DevicePolicyManager code exists in
 * this phase — the real implementation arrives in a later phase behind this
 * interface.
 */
interface EnforcementEngine {

    /**
     * Applies the restrictive policy for [session] using [lockTaskPolicy].
     * Idempotent: applying the same session twice is a no-op.
     */
    fun applyRestrictedPolicy(session: ProtectionSession, lockTaskPolicy: LockTaskPolicy)

    /** Removes the restriction and restores normal device policy. Idempotent. */
    fun restoreNormalPolicy()

    /**
     * Re-checks the live platform state and corrects it to match the persisted
     * session (for example after an app restart or a reboot).
     *
     * @return the status after reconciliation.
     */
    fun reconcile(): EnforcementStatus

    /** What the platform is currently enforcing. */
    fun currentStatus(): EnforcementStatus
}
