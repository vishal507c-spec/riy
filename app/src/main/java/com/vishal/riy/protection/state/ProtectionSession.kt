package com.vishal.riy.protection.state

import com.vishal.riy.protection.policy.ProtectionState

/**
 * Immutable snapshot of one protection session. Pure Kotlin.
 *
 * IMPORTANT — AUTHORITY RULE: the [expiryTime] of a live RESTRICTED session is
 * a mirror of the deadline owned by the existing
 * `com.vishal.riy.lock.LockEngine` / `LockStore`. This class must NEVER become
 * a second, competing source of truth for the 2-hour window. The lock engine's
 * persisted deadline remains authoritative; a session records the same
 * wall-clock window so the UI, integrity engine and event log can reason about
 * it, but only the lock engine's deadline decides when a restriction actually
 * ends.
 *
 * @param sessionId      stable unique id of the session.
 * @param state          the protection state this session represents.
 * @param startTime      epoch ms when the session began.
 * @param expiryTime     epoch ms when the session ends (0 / == [startTime] when
 *                       the state is not time-boxed).
 * @param reason         why the session exists (a [com.vishal.riy.protection.policy.ProtectionDecision] reason code).
 * @param policyVersion  the policy that produced it.
 * @param allowedPackages packages permitted during the restriction (empty until
 *                       the resolver computes them; never a source of truth).
 * @param blockedCount   number of apps restricted during the session.
 */
data class ProtectionSession(

    val sessionId: String,

    val state: ProtectionState,

    val startTime: Long,

    val expiryTime: Long,

    val reason: String,

    val policyVersion: Int,

    val allowedPackages: List<String> = emptyList(),

    val blockedCount: Int = 0,

) {

    /** True when this session is time-boxed and its deadline has passed. */
    fun isExpired(now: Long): Boolean = expiryTime > startTime && expiryTime <= now

    companion object {

        /** Sentinel for "no session is active". */
        val NONE: ProtectionSession? = null
    }
}
