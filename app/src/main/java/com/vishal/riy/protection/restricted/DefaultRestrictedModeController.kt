package com.vishal.riy.protection.restricted

import com.vishal.riy.lock.LockEngine
import com.vishal.riy.lock.LockStore
import com.vishal.riy.protection.enforcement.EnforcementEngine
import com.vishal.riy.protection.enforcement.LockTaskPolicy
import com.vishal.riy.protection.policy.ProtectionState
import com.vishal.riy.protection.state.ProtectionSession
import com.vishal.riy.protection.state.ProtectionStateStore

/**
 * THE implementation of [RestrictedModeController]. It owns the LIFECYCLE
 * COORDINATION of one restriction session and nothing else.
 *
 * DEPENDENCY DIRECTION (unchanged from the contract):
 *
 *     PolicyEngine  →  RestrictedModeController  →  EnforcementEngine
 *
 * Coordination means, precisely:
 *  - persisting the session so it survives a restart or a reboot;
 *  - delegating every platform write to the [EnforcementEngine];
 *  - ending the session when the authoritative deadline says it is over.
 *
 * WHAT IT DELIBERATELY DOES NOT DO
 *  - It never decides whether content is dangerous — that is the policy
 *    engine's decision. [enter] is reachable only from the protection pipeline.
 *  - It never starts a timer, never posts a delayed task and never owns a
 *    deadline. The authoritative 2-hour deadline stays in `LockEngine` /
 *    `LockStore`, exactly as Phase 1 left it. [recover] ASKS that deadline
 *    whether a session is still live instead of computing a second one.
 *  - It holds no Android object; only pure-Kotlin contracts are injected.
 */
class DefaultRestrictedModeController(

    private val enforcement: EnforcementEngine,

    private val store: ProtectionStateStore,

    private val lockStore: LockStore,

    private val clock: () -> Long = System::currentTimeMillis,

) : RestrictedModeController {

    /**
     * Begins coordinating [session] under [lockTaskPolicy]: persists it so the
     * session outlives the process, then asks the enforcement engine to apply
     * the platform policy. Applying is idempotent at every layer here.
     */
    @Synchronized
    override fun enter(session: ProtectionSession, lockTaskPolicy: LockTaskPolicy) {
        store.save(session)
        enforcement.applyRestrictedPolicy(session, lockTaskPolicy)
    }

    /**
     * Ends the current session: restores normal device policy first (so the
     * device becomes fully usable again) and only then drops the persisted
     * session. Idempotent — a second call when no session is live changes
     * nothing and performs no platform write.
     */
    @Synchronized
    override fun exit() {
        if (store.current() == null) return
        enforcement.restoreNormalPolicy()
        store.clear()
    }

    /**
     * Re-syncs an already-persisted session after a restart or reboot.
     *
     * LIVENESS IS NOT RECOMPUTED HERE. The existing `LockEngine`'s persisted
     * deadline is the single source of truth for when a 2-hour window ends, so
     * this method reads that deadline and acts on its verdict:
     *
     *   - no persisted session          → nothing to restore
     *   - session not time-boxed        → restore it as-is (no deadline applies)
     *   - deadline says still live      → re-apply the restriction
     *   - deadline says expired         → restore normal policy and clear it
     *
     * @return the restored session, or null if none should be live.
     */
    @Synchronized
    override fun recover(): ProtectionSession? {
        val session = store.load() ?: return null

        // A non-restricted session has nothing to re-enforce.
        if (!session.state.isRestrictedSession()) {
            store.clear()
            return null
        }

        // The authoritative deadline lives in LockEngine. This class never
        // becomes a competing source of truth for the 2-hour window.
        val lockState = lockStore.loadState()
        val now = clock()

        return when {
            // A time-boxed session whose deadline has passed is over.
            session.isExpired(now) || !LockEngine.isLocked(lockState, now) -> {
                exit()
                null
            }
            // Still live: re-apply exactly the persisted session.
            else -> {
                enforcement.applyRestrictedPolicy(session, persistedPolicyFor(session))
                session
            }
        }
    }

    /** The session this controller is currently coordinating, straight from the store. */
    override fun currentSession(): ProtectionSession? = store.current()

    /**
     * Reconstructs the lock-task policy for a persisted session. The persisted
     * [ProtectionSession.allowedPackages] is the recorded allowlist; the
     * conservative restrictive defaults keep the device usable (emergency
     * calling, notifications and global actions stay available; home and
     * overview stay blocked so no arbitrary app can be escaped into).
     */
    private fun persistedPolicyFor(session: ProtectionSession): LockTaskPolicy = LockTaskPolicy(
        allowedPackages = session.allowedPackages,
        allowHome = false,
        allowOverview = false,
        allowNotifications = true,
        allowSystemInfo = true,
        allowGlobalActions = true,
    )

    private fun ProtectionState.isRestrictedSession(): Boolean =
        this == ProtectionState.RESTRICTED || this == ProtectionState.HARDENED
}
