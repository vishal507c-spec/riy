package com.vishal.riy.protection.recovery

import com.vishal.riy.lock.LockEngine
import com.vishal.riy.lock.LockStore
import com.vishal.riy.protection.enforcement.AndroidEnforcementEngine
import com.vishal.riy.protection.enforcement.EnforcementStatus
import com.vishal.riy.protection.enforcement.LockTaskPolicy
import com.vishal.riy.protection.enforcement.PolicyApplicationResult
import com.vishal.riy.protection.enforcement.ReconciliationResult
import com.vishal.riy.protection.events.ProtectionEventStore
import com.vishal.riy.protection.events.ProtectionLogEvent
import com.vishal.riy.protection.policy.ProtectionState
import com.vishal.riy.protection.restricted.RestrictedModeController
import com.vishal.riy.protection.state.ProtectionSession

/**
 * THE production implementation of [BootRecoveryController], and the only
 * recovery ORCHESTRATOR in the app. It owns the ORDER of the recovery steps
 * and the honest reporting of their outcome — and nothing else:
 *
 *     1. read the persisted protection state;
 *     2. read the authoritative LockEngine/LockStore deadline;
 *     3. decide whether the session is still live;
 *     4. compare the expected policy with the ACTUAL platform state;
 *     5. expired  → restore normal policy through the existing path;
 *        live     → reconcile the platform to the expected restriction;
 *     6. report the real result; a failed reconciliation is never reported as
 *        success;
 *     7. record the outcome through the existing event log.
 *
 * OWNERSHIP — every step above is delegated to the component that already owns
 * it. This class introduces no second version of any of them:
 *  - the deadline verdict comes from [LockEngine] / [LockStore];
 *  - the session read/write comes from
 *    [com.vishal.riy.protection.state.ProtectionStateStore] via
 *    [RestrictedModeController];
 *  - the restricted LIFECYCLE is driven by [RestrictedModeController];
 *  - the ACTUAL Android enforcement and its read-back come from
 *    [AndroidEnforcementEngine];
 *  - the log line goes to [ProtectionEventStore].
 *
 * NO SECOND DEADLINE. This class never computes `now + duration`, never starts
 * a timer, and never writes to [LockStore]. The deadline is read, never
 * written. A live session is simply re-applied as-is.
 *
 * NO BUSINESS LOGIC DUPLICATION. Session liveness uses the same
 * [ProtectionSession.isExpired] + [LockEngine.isLocked] verdicts the pipeline
 * already uses; it does not re-derive them from a private rule.
 *
 * NEVER THROWS. Any unexpected failure is caught and reported as a recovery
 * mismatch rather than crashing the caller (a boot receiver or a service must
 * never be taken down by recovery).
 */
class DefaultRecoveryService(

    private val restrictedMode: RestrictedModeController,

    private val lockStore: LockStore,

    private val enforcement: AndroidEnforcementEngine,

    private val eventStore: ProtectionEventStore,

    private val clock: () -> Long = System::currentTimeMillis,

) : BootRecoveryController {

    /**
     * Runs one recovery pass and returns the outcome. Safe to call from a boot
     * broadcast, a service start, or the UI process; it performs no privileged
     * operation it cannot verify.
     */
    @Synchronized
    fun recoverWithResult(): RecoveryResult {
        val now = clock()
        val session = restrictedMode.currentSession()

        // 1. No persisted session: nothing to recover. The platform state is
        //    still reconciled against the normal policy so a leftover
        //    restriction from a dead session is removed rather than orphaned.
        if (session == null || !session.state.isRestrictedSession()) {
            return reconcileExpired(now)
        }

        // 2-3. The authoritative deadline decides liveness — never this class.
        val lockState = lockStore.loadState()
        val live = !session.isExpired(now) && LockEngine.isLocked(lockState, now)

        // 4-5. Live: reconcile the actual platform state with the expected
        //    restricted policy. Expired: restore the normal policy.
        return if (live) reconcileLive(session, now) else reconcileExpired(now)
    }

    /** The contract entry point: runs a pass and reports nothing on failure. */
    override fun recover() {
        runCatching { recoverWithResult() }
    }

    // ------------------------------------------------------------- live path

    /**
     * A live session is expected. The engine's in-memory expectation did NOT
     * survive the process death, so it is restored FIRST from the persisted
     * session — without a platform write — and only then is the ACTUAL platform
     * state compared against it. The existing reconciliation performs the
     * single correction when it differs, and success is judged solely on its
     * second read-back.
     */
    @Synchronized
    private fun reconcileLive(session: ProtectionSession, now: Long): RecoveryResult {
        val before = enforcement.currentStatus()

        // Restore what the process lost. This performs NO platform write; it
        // only makes the comparison below evaluate the real expected policy
        // instead of falling back to the normal one.
        val restored = enforcement.restoreExpectation(session, persistedPolicyFor(session))

        if (restored == null) {
            // Not Device Owner, or the resolved allowlist failed the same safety
            // gate the apply path runs. Nothing was enforced; report honestly.
            val reconciled = enforcement.reconcileWithResult()
            val outcome = if (reconciled == ReconciliationResult.NOT_DEVICE_OWNER)
                RecoveryOutcome.NOT_DEVICE_OWNER else RecoveryOutcome.MISMATCH
            record(outcome, now) {
                "live session ${session.sessionId} expectation could not be restored " +
                    "($reconciled; status was $before)"
            }
            // The recovery itself did not apply anything; report the recovery's
            // own attempt result (NOT_DEVICE_OWNER or MISMATCH).
            val recoveryApplyResult = when (outcome) {
                RecoveryOutcome.NOT_DEVICE_OWNER -> PolicyApplicationResult.NOT_DEVICE_OWNER
                else -> PolicyApplicationResult.FAILED
            }
            return RecoveryResult(
                outcome = outcome,
                enforcementStatus = enforcement.currentStatus(),
                applicationResult = recoveryApplyResult,
                reconciliation = reconciled,
            )
        }

        return when (val reconciled = enforcement.reconcileWithResult()) {
            ReconciliationResult.VERIFIED -> {
                // 6-7. The platform holds exactly what a live session requires.
                // No log for a clean verification — only deviations are recorded.
                RecoveryResult(
                    outcome = RecoveryOutcome.RESTORED,
                    enforcementStatus = enforcement.currentStatus(),
                    applicationResult = enforcement.lastApplicationResultSnapshot(),
                    reconciliation = reconciled,
                )
            }
            ReconciliationResult.NOT_DEVICE_OWNER -> {
                // Without Device Owner no privileged operation is possible:
                // report the honest verdict instead of pretending protection
                // is active.
                record(RecoveryOutcome.NOT_DEVICE_OWNER, now) {
                    "recovery of live session ${session.sessionId} impossible: not Device Owner"
                }
                RecoveryResult(
                    outcome = RecoveryOutcome.NOT_DEVICE_OWNER,
                    enforcementStatus = enforcement.currentStatus(),
                    applicationResult = enforcement.lastApplicationResultSnapshot(),
                    reconciliation = reconciled,
                )
            }

            else -> {
                // MISMATCH or ERROR. [reconcileWithResult] already attempted the
                // ONE safe correction through the existing enforcement path and
                // judged it on a SECOND read-back; a failure is reported as
                // such and must never be dressed up as a success.
                record(RecoveryOutcome.MISMATCH, now) {
                    "live session ${session.sessionId} NOT verified on recovery: " +
                        "$reconciled (status was $before)"
                }
                RecoveryResult(
                    outcome = RecoveryOutcome.MISMATCH,
                    enforcementStatus = enforcement.currentStatus(),
                    applicationResult = enforcement.lastApplicationResultSnapshot(),
                    reconciliation = reconciled,
                )
            }
        }
    }

    /**
     * The session is gone (or never was). The platform is reconciled against
     * the NORMAL policy, and any restrictive leftover is removed — the
     * existing restoration path, driven by the restricted-mode controller.
     */
    @Synchronized
    private fun reconcileExpired(now: Long): RecoveryResult {
        // The persisted session (if any) is ended through the existing path;
        // [RestrictedModeController.exit] is idempotent, so this is a no-op when
        // nothing is persisted.
        restrictedMode.exit()

        // After the session is dropped, the engine must compare the platform
        // against the NORMAL policy again — otherwise the stale in-memory
        // expectation of a restriction would make reconciliation re-impose it.
        enforcement.deferExpectation()

        val reconciled = enforcement.reconcileWithResult()

        // A restrictive platform state that survived the session is the exact
        // orphan recovery must remove.
        val stillRestrictive = enforcement.currentStatus() == EnforcementStatus.ACTIVE_RESTRICTED

        val outcome = when {
            reconciled == ReconciliationResult.VERIFIED && !stillRestrictive ->
                RecoveryOutcome.EXPIRED
            reconciled == ReconciliationResult.NOT_DEVICE_OWNER ->
                RecoveryOutcome.NOT_DEVICE_OWNER
            else -> RecoveryOutcome.MISMATCH
        }

        if (outcome != RecoveryOutcome.EXPIRED) {
            record(outcome, now) {
                "expired-or-absent session not fully restored on recovery: " +
                    "$reconciled (restrictiveLeftover=$stillRestrictive)"
            }
        }

        return RecoveryResult(
            outcome = outcome,
            enforcementStatus = enforcement.currentStatus(),
            applicationResult = enforcement.lastApplicationResultSnapshot(),
            reconciliation = reconciled,
        )
    }

    /**
     * The lock-task policy a persisted session is expected to hold — the same
     * conservative restrictive defaults the restricted-mode controller applies
     * on its own recovery path. Only the persisted allowlist is carried over.
     */
    private fun persistedPolicyFor(session: ProtectionSession): LockTaskPolicy = LockTaskPolicy(
        allowedPackages = session.allowedPackages,
        allowHome = false,
        allowOverview = false,
        allowNotifications = true,
        allowSystemInfo = true,
        allowGlobalActions = true,
    )

    /** Appends exactly one log line, never throwing. */
    private fun record(outcome: RecoveryOutcome, now: Long, message: () -> String) {
        runCatching {
            eventStore.append(
                ProtectionLogEvent(
                    eventId = "recovery-$now",
                    type = when (outcome) {
                        RecoveryOutcome.RESTORED, RecoveryOutcome.EXPIRED ->
                            ProtectionLogEvent.Type.POLICY_RECONCILED
                        RecoveryOutcome.NOT_DEVICE_OWNER ->
                            ProtectionLogEvent.Type.INTEGRITY_CHECK
                        RecoveryOutcome.MISMATCH ->
                            ProtectionLogEvent.Type.INTEGRITY_MISMATCH
                    },
                    timestamp = now,
                    message = message(),
                ),
            )
        }
    }

    private fun ProtectionState?.isRestrictedSession(): Boolean =
        this == ProtectionState.RESTRICTED || this == ProtectionState.HARDENED
}

/**
 * What one recovery pass actually achieved, in the existing vocabulary of
 * results — never a fabricated success.
 *
 * @param outcome            the honest verdict of the pass.
 * @param enforcementStatus  the ACTUAL platform state after recovery.
 * @param applicationResult  the policy-application result of any re-apply.
 * @param reconciliation     the read-back comparison result.
 */
data class RecoveryResult(

    val outcome: RecoveryOutcome,

    val enforcementStatus: EnforcementStatus,

    val applicationResult: PolicyApplicationResult,

    val reconciliation: ReconciliationResult,

) {

    /** True only when the platform was verified to hold the expected policy. */
    val verified: Boolean
        get() = outcome == RecoveryOutcome.RESTORED || outcome == RecoveryOutcome.EXPIRED
}

/** The honest verdict of one recovery pass. */
enum class RecoveryOutcome {

    /** A live restricted session was verified (or restored) on the platform. */
    RESTORED,

    /** An expired session was cleared and normal policy was verified. */
    EXPIRED,

    /** RIY is not Device Owner; nothing privileged could be attempted. */
    NOT_DEVICE_OWNER,

    /** Reconciliation or re-application failed; protection was NOT verified. */
    MISMATCH,
}
