package com.vishal.riy.protection.intelligence

import com.vishal.riy.protection.policy.ProtectionPolicy

/**
 * The deterministic configuration for the DETECTION window — how far back the
 * correlation rules look for corroborating observations.
 *
 * ARCHITECTURE RULE — this is NOT a restriction deadline. It is a detection
 * concept only, kept in this file and nowhere else:
 *
 *  - Detection window ([CORRELATION_WINDOW_MS]) decides whether accumulated
 *    OBSERVATIONS corroborate each other.
 *  - Restriction deadline ([com.vishal.riy.lock.LockEngine.LOCK_DURATION_MS],
 *    persisted by [com.vishal.riy.lock.LockStore]) decides when an armed
 *    restriction ENDS.
 *
 * The two are never mixed, never added together and never read by the same
 * rule. Only [LockEngine] may say when a restriction ends; this object says
 * nothing about that.
 *
 * Values are deliberate and testable rather than learned: a real browsing
 * session resolves a page's hosts within seconds, while a stray background
 * lookup from an unrelated app is a one-off. The window is wide enough to catch
 * the former and short enough that an unrelated match an hour later cannot be
 * wedged onto an old observation.
 */
object ProtectionCorrelationWindow {

    /**
     * How long an observation can be corroborated by a later one. 5 minutes.
     */
    const val CORRELATION_WINDOW_MS: Long = 5L * 60L * 1_000L

    /**
     * How long two observations of the SAME domain must be apart to count as
     * two independent observations (a genuine repeat navigation rather than one
     * page load's A/AAAA/retry burst). Mirrors [com.vishal.riy.lock.LockEngine.DEDUP_WINDOW_MS]
     * on purpose, so the intelligence layer and the lock engine can never
     * disagree about what "one detection" means.
     */
    const val SAME_DOMAIN_REPEAT_MS: Long = 60_000L

    /** How many observations are retained at most (bounded history). */
    const val MAX_RETAINED: Int = 24

    /** The policy these rules belong to, stamped onto every observation. */
    val policyVersion: Int
        get() = ProtectionPolicy.CURRENT_POLICY_VERSION
}
