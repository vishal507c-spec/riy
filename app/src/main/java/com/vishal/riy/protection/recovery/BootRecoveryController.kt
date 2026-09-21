package com.vishal.riy.protection.recovery

/**
 * Restores the correct protection state after the process restarts or the
 * device reboots.
 *
 * Conceptual flow (not yet connected to Android BOOT_COMPLETED):
 *
 *     BOOT
 *       → load persisted state
 *       → validate state
 *       → check expiry
 *       → if still active → restore the restriction
 *       → if expired       → transition to NORMAL
 *       → reconcile DevicePolicyManager state
 *       → refresh the UI
 *
 * The lock engine's persisted deadline stays the authority for whether a
 * 2-hour window is still live; this controller only acts on that truth.
 *
 * Phase 2 establishes the contract only; the BOOT_COMPLETED wiring arrives
 * later and will NOT modify the existing blocker BootReceiver's VPN behaviour.
 */
fun interface BootRecoveryController {

    /** Runs the recovery flow. Never throws; a failure logs and falls back to safe state. */
    fun recover()
}
