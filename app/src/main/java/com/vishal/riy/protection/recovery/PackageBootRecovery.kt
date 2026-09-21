package com.vishal.riy.protection.recovery

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.vishal.riy.admin.UninstallProtection
import com.vishal.riy.protection.ProtectionEventProcessor
import com.vishal.riy.protection.integrity.FreezerProtectionMonitor
import com.vishal.riy.protection.integrity.PackageIntegrityEngine

/**
 * Boot recovery. On BOOT_COMPLETED it re-verifies RIY's package state and
 * re-applies the one piece of protection that is legitimately idempotent to
 * re-apply from the Device Owner seat: uninstall blocking
 * (see [UninstallProtection], which stays the sole authority for that).
 *
 * Hard rules this component obeys:
 *  - it NEVER reinstalls RIY;
 *  - it NEVER downloads or silently installs any package;
 *  - it NEVER modifies the VPN filter, the lock engine, or SafeSearch;
 *  - if RIY is genuinely missing it records that fact and does nothing else —
 *    a missing package cannot be recovered by an app that no longer exists.
 */
class PackageBootRecovery : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return

        Log.i(TAG, "boot completed; verifying package protection")

        try {
            val state = PackageIntegrityEngine(context).verify()
            val level = FreezerProtectionMonitor(context).observe()

            Log.i(
                TAG,
                "boot state: verified=${state.verified}, level=$level, " +
                    "owner=${state.deviceOwner}, launcher=${state.launcherActivityResolves}",
            )

            // The single legitimate, idempotent re-application. UninstallProtection
            // itself decides whether it is applicable; this only re-invokes it.
            if (state.deviceOwner && !state.uninstallBlocked) {
                Log.w(TAG, "uninstall protection missing after boot; re-applying")
                UninstallProtection.apply(context)
            }

            // PHASE 6 — reconcile the persisted protection session with the
            // ACTUAL platform state: re-apply a live restriction, or clear a
            // restriction whose 2-hour deadline has passed. This uses the
            // existing recovery orchestrator and changes no deadline.
            try {
                val recovery = ProtectionEventProcessor.recoveryForContext(context).recoverWithResult()
                Log.i(
                    TAG,
                    "protection recovery: outcome=${recovery.outcome}, " +
                        "status=${recovery.enforcementStatus}, " +
                        "applied=${recovery.applicationResult}, " +
                        "reconciliation=${recovery.reconciliation}",
                )
            } catch (e: Exception) {
                // Recovery must never take the boot broadcast down.
                Log.e(TAG, "protection recovery failed", e)
            }

            state.issues.forEach { issue ->
                Log.w(TAG, "boot integrity issue: ${issue.component} — ${issue.description}")
            }
        } catch (e: Exception) {
            // A boot receiver must never crash the system's delivery.
            Log.e(TAG, "boot recovery failed", e)
        }
    }

    private companion object {
        const val TAG = "RiyBootRecovery"
    }
}
