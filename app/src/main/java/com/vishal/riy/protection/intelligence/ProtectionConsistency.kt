package com.vishal.riy.protection.intelligence

import com.vishal.riy.lock.LockEngine
import com.vishal.riy.lock.LockStore
import com.vishal.riy.protection.enforcement.AndroidEnforcementEngine
import com.vishal.riy.protection.enforcement.EnforcementEngine
import com.vishal.riy.protection.enforcement.EnforcementStatus
import com.vishal.riy.protection.enforcement.PackageManagerAppPolicyResolver
import com.vishal.riy.protection.enforcement.platform.AndroidDevicePolicyBoundary
import com.vishal.riy.protection.enforcement.platform.AndroidPackageDiscoveryBoundary
import com.vishal.riy.protection.integrity.IntegrityIssue
import com.vishal.riy.protection.policy.ProtectionPolicy
import com.vishal.riy.protection.policy.ProtectionState
import com.vishal.riy.protection.state.PrefsProtectionStateStore
import com.vishal.riy.protection.state.ProtectionSession
import com.vishal.riy.protection.state.ProtectionStateStore

/**
 * The Phase 10 self-healing check for the PROTECTION layer's own bookkeeping.
 *
 * It verifies that the persisted protection session, the authoritative
 * [LockEngine] deadline and the live platform enforcement AGREE with each
 * other, and it repairs the one thing it is allowed to repair — the session's
 * mirrored expiry — through the full DETECT → RECONCILE → RESTORE → READ BACK →
 * VERIFY cycle. A repair it cannot verify is reported as a mismatch, never as a
 * success.
 *
 * OWNERSHIP — this class owns ONE thing: consistency detection and mirror
 * repair. It owns no rule about WHEN a restriction ends:
 *
 *  - the deadline is READ from [LockStore] and NEVER written. The sole deadline
 *    authority stays exactly where Phase 1 put it. This class computes no
 *    `now + duration`, arms nothing, extends nothing and shortens nothing;
 *  - a protection STATE transition is never performed here. When the mirror is
 *    repaired, the session's [ProtectionSession.state] is carried through
 *    UNCHANGED and only its [ProtectionSession.expiryTime] is corrected to the
 *    authoritative value — which is precisely what [ProtectionSession]'s own
 *    documented authority rule requires;
 *  - the PLATFORM is never written here either. [EnforcementEngine.currentStatus]
 *    is a read; the actual platform reconciliation belongs to
 *    [com.vishal.riy.protection.recovery.DefaultRecoveryService] and
 *    [com.vishal.riy.protection.enforcement.AndroidEnforcementEngine], the
 *    existing owners. A mismatch this class finds is REPORTED so they can act
 *    and so the UI can show the honest "Protection Needs Attention" state;
 *  - no recovery path is created here, so there is nothing here that could
 *    create a duplicate deadline. The audit assertion for that is structural:
 *    this class holds no deadline-writing API at all.
 *
 * Pure Kotlin apart from the injected seams; deterministic given the same
 * stores, the same clock and the same enforcement read.
 */
class ProtectionConsistency(

    private val stateStore: ProtectionStateStore,

    private val lockStore: LockStore,

    private val enforcement: EnforcementEngine,

    private val clock: () -> Long = System::currentTimeMillis,

) {

    /**
     * Runs one verification pass (with mirror repair) and reports the outcome.
     * Never throws: an unreadable store or enforcement failure degrades to an
     * honest unverified report rather than a reassuring one.
     */
    fun verify(): Report {
        val now = clock()
        val session = stateStore.load()
        val lockState = lockStore.loadState()
        val deadline = lockState.lockEndEpochMillis
        val deadlineLive = LockEngine.isLocked(lockState, now)

        val sessionLive = session != null &&
            session.state.isRestrictedSession() &&
            !session.isExpired(now) &&
            deadlineLive

        val policyConsistent = isPolicyConsistent(session)
        val sessionConsistent = isSessionConsistent(session, deadline, sessionLive)
        val lockTaskConsistent = isLockTaskConsistent(sessionLive)

        // DETECT → RECONCILE → RESTORE → READ BACK → VERIFY. A repair is only
        // claimed when it was actually needed AND the second read confirms it.
        val repairVerified = repairMirrorIfNeeded(session, deadline, sessionLive, sessionConsistent)
        val mirrorRepaired = !sessionConsistent && repairVerified

        val issues = buildList {
            if (!policyConsistent) {
                add(
                    IntegrityIssue(
                        component = COMPONENT_SESSION,
                        description = "persisted session policy version " +
                            "(${session?.policyVersion}) does not match the current policy " +
                            "(${ProtectionPolicy.CURRENT_POLICY_VERSION})",
                        severity = IntegrityIssue.Severity.WARN,
                    ),
                )
            }
            if (!sessionConsistent) {
                add(
                    IntegrityIssue(
                        component = COMPONENT_SESSION,
                        description = "session mirror (${session?.expiryTime}) diverged from " +
                            "the authoritative LockEngine deadline ($deadline); the mirror is " +
                            "the field that was corrected",
                        severity = if (mirrorRepaired) IntegrityIssue.Severity.INFO
                        else IntegrityIssue.Severity.ERROR,
                    ),
                )
            }
            if (!lockTaskConsistent) {
                add(
                    IntegrityIssue(
                        component = COMPONENT_LOCK_TASK,
                        description = "platform enforcement does not match the expected policy " +
                            "for a ${if (sessionLive) "live" else "expired"} session",
                        severity = IntegrityIssue.Severity.WARN,
                    ),
                )
            }
        }

        return Report(
            policyConsistent = policyConsistent,
            // Consistent either because it always was, or because a needed
            // repair was verified on read-back. A repair that could not be
            // verified is reported as inconsistent — never as success.
            sessionConsistent = sessionConsistent || mirrorRepaired,
            lockTaskConsistent = lockTaskConsistent,
            mirrorRepaired = mirrorRepaired,
            issues = issues,
        )
    }

    // ------------------------------------------------------------- the checks

    /** The session was written by the policy that is current now. */
    private fun isPolicyConsistent(session: ProtectionSession?): Boolean =
        session == null || session.policyVersion == ProtectionPolicy.CURRENT_POLICY_VERSION

    /**
     * For a live restricted session, the mirrored expiry MUST equal the
     * authoritative [LockEngine] deadline. Every other session shape carries no
     * deadline relationship at all, so it is consistent by definition.
     */
    private fun isSessionConsistent(
        session: ProtectionSession?,
        deadline: Long,
        sessionLive: Boolean,
    ): Boolean = !sessionLive || session?.expiryTime == deadline

    /**
     * What the platform is ACTUALLY enforcing must match what the backend
     * requires: a restrictive allowlist while a session is live, the normal
     * policy otherwise. A read failure is reported as inconsistent rather than
     * assumed fine.
     */
    private fun isLockTaskConsistent(sessionLive: Boolean): Boolean = try {
        val status = enforcement.currentStatus()
        if (sessionLive) status == EnforcementStatus.ACTIVE_RESTRICTED
        else status == EnforcementStatus.NORMAL
    } catch (_: Throwable) {
        false
    }

    // ------------------------------------------------------------ the repair

    /**
     * DETECT → RECONCILE → RESTORE → READ BACK → VERIFY, applied to the one
     * field this class may correct. The deadline itself is untouched; only the
     * session's mirror of it is rewritten, then read straight back and compared
     * again. A repair that could not be verified returns false, which the caller
     * reports as a mismatch — never as success.
     *
     * @return true when the mirror is consistent now — because no repair was
     *   needed, or because a needed repair was written AND verified on a second
     *   read. False only when a repair was needed and could not be verified.
     */
    private fun repairMirrorIfNeeded(
        session: ProtectionSession?,
        deadline: Long,
        sessionLive: Boolean,
        sessionConsistent: Boolean,
    ): Boolean {
        if (sessionConsistent) return true
        if (!sessionLive || session == null) return true

        // RECONCILE + RESTORE: correct the mirror to the authoritative value.
        // The deadline above is read, never written; only the session's copy of
        // it changes, and the session's STATE is carried through unchanged.
        val restored = session.copy(expiryTime = deadline)
        return try {
            stateStore.save(restored)
            // READ BACK + VERIFY: the store must now hold exactly the
            // authoritative value, or the repair is reported as a failure.
            stateStore.load()?.expiryTime == deadline
        } catch (_: Throwable) {
            false
        }
    }

    private fun ProtectionState.isRestrictedSession(): Boolean =
        this == ProtectionState.RESTRICTED || this == ProtectionState.HARDENED

    /**
     * The outcome of one pass. [sessionConsistent] is only true when the mirror
     * genuinely agrees with the authoritative deadline AFTER any repair — a
     * failed repair is reported as inconsistent, exactly as an unresolved
     * divergence would be.
     */
    data class Report(

        val policyConsistent: Boolean,

        val sessionConsistent: Boolean,

        val lockTaskConsistent: Boolean,

        /** True when a divergent mirror was written and verified on read-back. */
        val mirrorRepaired: Boolean,

        val issues: List<IntegrityIssue>,
    ) {

        /** True only when every consistency check passed. */
        val consistent: Boolean
            get() = policyConsistent && sessionConsistent && lockTaskConsistent
    }

    companion object {

        const val COMPONENT_SESSION = "protection_session"
        const val COMPONENT_LOCK_TASK = "lock_task"

        /**
         * The production graph: shares the SAME state store, lock store and
         * enforcement engine the pipeline and the recovery service already use,
         * so this check reads exactly the truth the pipeline wrote — never a
         * private copy of it.
         */
        fun forContext(context: android.content.Context): ProtectionConsistency {
            val app = context.applicationContext
            val riyPackageName = app.packageName
            val discovery = AndroidPackageDiscoveryBoundary(app)

            return ProtectionConsistency(
                stateStore = PrefsProtectionStateStore(app),
                lockStore = com.vishal.riy.lock.PrefsLockStore(app),
                enforcement = AndroidEnforcementEngine(
                    devicePolicy = AndroidDevicePolicyBoundary(app),
                    discovery = discovery,
                    appPolicyResolver = PackageManagerAppPolicyResolver(discovery, riyPackageName),
                    riyPackageName = riyPackageName,
                ),
            )
        }
    }
}
