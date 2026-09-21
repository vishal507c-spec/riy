package com.vishal.riy.debug

import android.app.Activity
import android.os.Bundle
import android.util.Log
import com.vishal.riy.BuildConfig
import com.vishal.riy.blocker.Blocklist
import com.vishal.riy.protection.ProtectionEventProcessor
import com.vishal.riy.protection.adultDomainLookupEvent
import com.vishal.riy.protection.enforcement.AndroidEnforcementEngine
import com.vishal.riy.protection.enforcement.PolicyApplicationResult
import com.vishal.riy.protection.enforcement.ReconciliationResult
import com.vishal.riy.protection.enforcement.platform.AndroidDevicePolicyBoundary
import com.vishal.riy.protection.enforcement.platform.AndroidPackageDiscoveryBoundary
import com.vishal.riy.protection.enforcement.PackageManagerAppPolicyResolver
import com.vishal.riy.protection.policy.ProtectionState
import com.vishal.riy.protection.state.ProtectionSession
import com.vishal.riy.lock.LockEngine

/**
 * INTERNAL TEST HOOK — Phase 4 only.
 *
 * This Activity lives in the DEBUG source set, so it is compiled OUT of every
 * release build and cannot exist on a production device. It is reachable only
 * from an adb shell (`am start`), never from the app's own UI: it has no
 * launcher icon, it is not exported, and it renders nothing.
 *
 * It exists so the real DevicePolicyManager enforcement can be exercised by
 * hand BEFORE any automatic detection wiring is connected. It deliberately:
 *  - never decides that content is dangerous (that is the policy engine);
 *  - never weakens protection, and never removes Device Owner or uninstall
 *    protection;
 *  - refuses to enter lock-task unless the resolved allowlist already passed
 *    the engine's pre-flight safety check, so it can never self-lock the
 *    device.
 *
 * Usage:
 *   adb shell am start -n com.vishal.riy/.debug.EnforcementTestActivity \
 *       -e step diagnostics
 *   ... -e step fulltest
 *   ... -e step enter     (applies the restriction AND enters lock-task mode)
 *   ... -e step exit      (leaves lock-task and restores normal policy)
 *   ... -e step pipeline -e domain <blocked-domain>
 *                         (Phase 5: runs ONE adult-domain DNS event through the
 *                          real production chain; the domain must already be on
 *                          the production Blocklist)
 *
 * Remove this whole file (and src/debug/AndroidManifest.xml) before release.
 */
class EnforcementTestActivity : Activity() {

    // Reuse a single processor instance to match production VPN service pattern.
    // Creating a new processor per call breaks escalation persistence because
    // SharedPreferences.apply() is async and the next processor reads stale state.
    private val processor: ProtectionEventProcessor by lazy {
        ProtectionEventProcessor.forContext(this)
    }

    private val engine: AndroidEnforcementEngine by lazy {
        AndroidEnforcementEngine(
            devicePolicy = AndroidDevicePolicyBoundary(this),
            discovery = AndroidPackageDiscoveryBoundary(this),
            appPolicyResolver = PackageManagerAppPolicyResolver(
                AndroidPackageDiscoveryBoundary(this),
                packageName,
            ),
            riyPackageName = packageName,
        )
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Defense in depth: this class only exists in debug builds, but it must
        // never run anywhere a release build could reach.
        if (!BuildConfig.DEBUG) {
            Log.e(TAG, "test hook executed outside a debug build — refusing")
            finish()
            return
        }

        when (intent.getStringExtra(EXTRA_STEP)) {
            STEP_DIAGNOSTICS -> {
                log(engine.diagnostics().toReportString())
                finish()
            }
            STEP_FULLTEST -> {
                runFullTest()
                finish()
            }
            STEP_ENTER -> enterRestrictedMode()
            STEP_EXIT -> {
                exitRestrictedMode()
                finish()
            }
            STEP_PIPELINE -> {
                runPipelineTest(intent.getStringExtra(EXTRA_DOMAIN).orEmpty())
                finish()
            }
            else -> {
                log("Unknown/missing step. Use: $STEP_DIAGNOSTICS, $STEP_FULLTEST, $STEP_ENTER, $STEP_EXIT, $STEP_PIPELINE")
                finish()
            }
        }
    }

    /**
     * The manual verification sequence: diagnose, apply, verify from the
     * read-back, then restore and confirm nothing else was weakened. This
     * covers items 1-7 and 11-13 of the Phase 4 device checklist; the app
     * launch checks (8-10) need the `enter` step plus a human at the screen.
     */
    private fun runFullTest() {
        log("=== Phase 4 enforcement full test ===")

        // 1. Device Owner detected.
        val report = engine.diagnostics()
        log(report.toReportString())
        if (!report.deviceOwner) {
            log("FAIL: not Device Owner — provision with `dpm set-device-owner` first")
            return
        }
        log("1. Device Owner detected: ${report.deviceOwner}")

        // 2-4. RIY, phone, wallet resolution.
        log("2. RIY package present: ${report.riyPackagePresent} (enabled=${report.riyEnabled})")
        log("3. Phone package resolved: ${report.phonePackage ?: "UNRESOLVED"}")
        log("4. Wallet package resolved: ${report.walletPackage ?: "UNRESOLVED (acceptable)"}")

        // 5-6. Apply the restrictive policy (this includes the safety gate).
        val session = testSession()
        val applied = engine.applyRestrictedPolicyWithResult(session, com.vishal.riy.protection.enforcement.LockTaskPolicy())
        log("5-6. Policy application result: $applied")
        if (applied != PolicyApplicationResult.APPLIED) {
            log("FAIL: policy was not applied ($applied) — see the diagnostics above")
            return
        }
        log("Generated allowlist: ${engine.diagnostics().lockTaskAllowlist}")

        // 7. RIY can remain running (it is in its own allowlist).
        log("7. RIY in lock-task allowlist: ${engine.diagnostics().lockTaskAllowlist.contains(packageName)}")

        // Reconciliation must confirm from the read-back.
        val reconciliation = engine.reconcileWithResult()
        log("Reconciliation: $reconciliation")
        if (reconciliation != ReconciliationResult.VERIFIED) {
            log("FAIL: reconciliation did not verify ($reconciliation)")
        }

        // 11. Restore normal policy.
        val restored = engine.restoreNormalPolicyWithResult()
        log("11. Normal policy restored: $restored")

        // 12-13. Nothing else was weakened.
        val after = engine.diagnostics()
        log("12. Device Owner still active: ${after.deviceOwner}")
        log("13. Uninstall protection still active: ${after.uninstallProtected}")
        log("    Admin component: ${after.adminComponent} (active=${after.adminActive})")

        val ok = after.deviceOwner && after.uninstallProtected && after.adminActive &&
            restored == PolicyApplicationResult.APPLIED
        log(if (ok) "=== Phase 4 full test: PASS ===" else "=== Phase 4 full test: FAIL ===")
    }

    /**
     * Applies the restriction AND enters lock-task mode, so a human can verify
     * by hand which apps launch. The engine's safety gate runs first; if the
     * allowlist is not provably usable, lock-task is NOT entered.
     */
    private fun enterRestrictedMode() {
        val applied = engine.applyRestrictedPolicyWithResult(
            testSession(),
            com.vishal.riy.protection.enforcement.LockTaskPolicy(),
        )
        log("enter: application result = $applied")
        log(engine.diagnostics().toReportString())

        if (applied != PolicyApplicationResult.APPLIED) {
            log("enter: REFUSING to enter lock-task — allowlist did not pass the safety gate")
            finish()
            return
        }

        // RIY is always in its own allowlist, so this activity may start and may
        // also stop lock-task — that is the guaranteed way back out.
        try {
            startLockTask()
            log("enter: lock-task mode started; allowed apps = ${engine.diagnostics().lockTaskAllowlist}")
            log("enter: to leave, run: am start -n $packageName/.debug.EnforcementTestActivity -e step exit")
        } catch (e: Exception) {
            log("enter: startLockTask failed: ${e.message}")
            engine.restoreNormalPolicyWithResult()
            finish()
        }
    }

    /**
     * Leaves lock-task and restores normal policy. Ends the session through the
     * production restricted-mode controller so nothing is left behind in the
     * persisted protection state. Always safe to call.
     */
    private fun exitRestrictedMode() {
        try {
            stopLockTask()
        } catch (e: Exception) {
            log("exit: not in lock-task (${e.message})")
        }
        ProtectionEventProcessor.forContext(this).endActiveSession()
        log("exit: normal policy restored and protection session ended")
        log(engine.diagnostics().toReportString())
    }

    /**
     * Phase 5 CONTROLLED INTEGRATION TEST (debug builds only).
     *
     * Submits exactly ONE adult-domain DNS event through the real production
     * chain — the same [ProtectionEventProcessor.forContext] graph the
     * BlockerVpnService builds — so the whole pipeline (risk → policy → state →
     * restricted mode → enforcement) is exercised on the device WITHOUT
     * depending on live network traffic.
     *
     * Safety gates this hook keeps:
     *  - it refuses to run outside a debug build (see [onCreate]);
     *  - it refuses any domain that is NOT already on the production
     *    Blocklist, so a test can never widen what the app blocks;
     *  - it invents no new enforcement path: the restriction is applied by the
     *    existing engine under the existing safety gate.
     */
    private fun runPipelineTest(domain: String) {
        log("=== Phase 5 controlled pipeline test (domain='$domain') ===")

        if (domain.isBlank()) {
            log("FAIL: no domain supplied (-e domain <blocked-domain>)")
            return
        }

        // Only a domain the production filter genuinely blocks may be used.
        val blocklist = try {
            Blocklist(
                assets.open(BLOCKLIST_ASSET).bufferedReader().useLines { Blocklist.parseRules(it) },
            )
        } catch (e: Exception) {
            log("FAIL: production blocklist could not be loaded (${e.message})")
            return
        }
        if (!blocklist.contains(domain)) {
            log("REFUSED: '$domain' is not on the production Blocklist; nothing was submitted")
            return
        }

        val result = processor.submit(adultDomainLookupEvent(domain, System.currentTimeMillis()))

        log("decision: state=${result.decision.nextState} reason=${result.decision.reason} " +
            "policyVersion=${result.decision.policyVersion}")
        log("enforcement result: ${result.enforcementResult ?: "not attempted"}")
        log("diagnostics: ${engine.diagnostics().toReportString().replace("\n", " | ")}")

        val ok = result.decision.nextState == ProtectionState.RESTRICTED &&
            result.enforcementResult == PolicyApplicationResult.APPLIED
        log(if (ok) "=== Phase 5 pipeline test: PASS ===" else "=== Phase 5 pipeline test: FAIL ===")
    }

    /**
     * The session this hook tests with. Its expiry MIRRORS the duration the one
     * deadline owner defines — [LockEngine.LOCK_DURATION_MS] — rather than
     * restating the number, so a test can never drift from the production
     * contract. The authoritative deadline itself is still armed by LockEngine
     * through the real pipeline (see [runPipelineTest]); this object is only
     * used by the Phase 4 manual [STEP_ENTER]/[STEP_FULLTEST] steps.
     */
    private fun testSession(): ProtectionSession = ProtectionSession(
        sessionId = "phase4-manual-test",
        state = ProtectionState.RESTRICTED,
        startTime = System.currentTimeMillis(),
        expiryTime = System.currentTimeMillis() + LockEngine.LOCK_DURATION_MS,
        reason = "phase4_manual_test",
        policyVersion = 1,
    )

    private fun log(message: String) {
        Log.i(TAG, message)
    }

    private companion object {
        const val TAG = "RiyEnforcementTest"
        const val EXTRA_STEP = "step"
        const val EXTRA_DOMAIN = "domain"
        const val STEP_DIAGNOSTICS = "diagnostics"
        const val STEP_FULLTEST = "fulltest"
        const val STEP_ENTER = "enter"
        const val STEP_EXIT = "exit"
        const val STEP_PIPELINE = "pipeline"
        const val BLOCKLIST_ASSET = "blocklist.txt"
    }
}
