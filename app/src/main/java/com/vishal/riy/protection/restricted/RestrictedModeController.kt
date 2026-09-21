package com.vishal.riy.protection.restricted

import com.vishal.riy.protection.enforcement.LockTaskPolicy
import com.vishal.riy.protection.state.ProtectionSession

/**
 * Owns the LIFECYCLE COORDINATION of a restriction session: starting the
 * session, keeping the countdown bound to the backend deadline, and ending the
 * session when the policy engine says it is over.
 *
 * DEPENDENCY DIRECTION (enforced by design):
 *
 *     PolicyEngine  →  RestrictedModeController  →  EnforcementEngine
 *
 * The controller executes the policy engine's decisions; it never originates
 * one. In particular the UI may NOT drive this controller to escape a
 * restriction — [enter] is reachable only from the protection pipeline, and
 * [exit] only from a policy decision (session expiry or reconciliation).
 *
 * Phase 2 establishes the contract only.
 */
interface RestrictedModeController {

    /**
     * Begins coordinating [session] under [lockTaskPolicy]. Delegates the
     * platform work to the enforcement engine.
     */
    fun enter(session: ProtectionSession, lockTaskPolicy: LockTaskPolicy)

    /**
     * Ends the current session and restores normal policy. Called by the
     * pipeline on expiry — there is no UI path to it.
     */
    fun exit()

    /**
     * Re-syncs an already-persisted session after a restart or reboot
     * (the counterpart of [com.vishal.riy.protection.recovery.BootRecoveryController]).
     *
     * @return the restored session, or null if none should be live.
     */
    fun recover(): ProtectionSession?

    /** The session this controller is currently coordinating, or null. */
    fun currentSession(): ProtectionSession?
}
