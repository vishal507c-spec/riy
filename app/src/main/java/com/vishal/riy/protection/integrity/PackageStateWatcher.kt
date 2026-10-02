package com.vishal.riy.protection.integrity

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

/**
 * Package-state watchdog. It does NOT poll: it wakes only on real Android
 * package lifecycle broadcasts, which is exactly the set of events an install,
 * update, enable/disable, or OEM freezer actuation produces.
 *
 * TWO DUTIES, kept separate:
 *  1. RIY itself (package == RIY): re-verify integrity (existing behaviour).
 *     A same-package PACKAGE_REPLACED is the legitimate self-update flow and
 *     is NEVER blocked — only verified and logged.
 *  2. EVERY OTHER package: funnel into [ProtectionReconciler], the single
 *     reconciliation entry point. While a restriction is live, a newly
 *     installed / replaced / enabled package is immediately reconciled against
 *     the protected-mode allowlist (TeraBox-family → hidden+suspended;
 *     unknown sideloads → suspended). While protection is merely wanted, only
 *     explicit bypass apps (TeraBox-family) are neutralized; everything else
 *     (Telegram, legitimate apps, browsers) is untouched.
 *
 * It never crashes the app, never reinstalls/downloads anything, and never
 * inspects message or media content — only package identity and the persisted
 * protection verdict.
 */
class PackageStateWatcher : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val pkg = intent.data?.schemeSpecificPart
        if (pkg.isNullOrBlank()) return

        when (intent.action) {
            Intent.ACTION_PACKAGE_ADDED -> {
                if (pkg == context.packageName) {
                    verify("package_added_self", context)
                } else {
                    // A genuine install — could be an unauthorized APK route.
                    // Replacing an update sends ADDED with REPLACING extra; a
                    // fresh install does not. Either way the reconciler decides:
                    // RIY updates are exempt, TeraBox is neutralized, Telegram
                    // and allowlisted apps are untouched.
                    val replacing = intent.getBooleanExtra(Intent.EXTRA_REPLACING, false)
                    Log.i(TAG, "package_added pkg=$pkg replacing=$replacing")
                    ProtectionReconciler.reconcilePackage(context, pkg, "package_added")
                    if (pkg == context.packageName) verify("package_added", context)
                }
            }

            Intent.ACTION_PACKAGE_CHANGED -> {
                // Enabled/disabled state changed (user, installer, or freezer).
                if (pkg == context.packageName) {
                    verify("package_changed", context)
                } else {
                    // A package that was disabled to dodge hardening and is now
                    // re-enabled mid-restriction must be reconciled again.
                    ProtectionReconciler.reconcilePackage(context, pkg, "package_changed")
                }
            }

            Intent.ACTION_PACKAGE_REPLACED -> {
                if (pkg == context.packageName) {
                    verify("package_replaced", context)
                } else {
                    // An update to a third-party app (possibly a bypass app
                    // updating itself). Re-harden when protection requires it.
                    ProtectionReconciler.reconcilePackage(context, pkg, "package_replaced")
                }
            }

            Intent.ACTION_PACKAGE_REMOVED -> {
                val replacing = intent.getBooleanExtra(Intent.EXTRA_REPLACING, false)
                if (pkg == context.packageName) {
                    // Replacing an update sends REMOVED first; a genuine removal of
                    // a Device Owner package is blocked by the framework, so this
                    // path should never succeed. Verify rather than assume.
                    verify("package_removed", context)
                } else if (!replacing) {
                    // A genuine removal reduces risk; still run a light
                    // reconciliation so stale hardening cannot orphan state.
                    Log.i(TAG, "package_removed pkg=$pkg")
                    ProtectionReconciler.reconcileAll(context, "package_removed:$pkg")
                }
                // else: REMOVED as part of a replace — the matching ADDED /
                // REPLACED broadcast performs the reconciliation.
            }

            Intent.ACTION_PACKAGE_FULLY_REMOVED -> {
                if (pkg != context.packageName) {
                    Log.i(TAG, "package_fully_removed pkg=$pkg")
                    ProtectionReconciler.reconcileAll(context, "package_fully_removed:$pkg")
                }
            }
        }
    }

    private fun verify(trigger: String, context: Context) {
        try {
            val state = PackageIntegrityEngine(context).verify()
            val level = FreezerProtectionMonitor(context).observe()
            Log.i(
                TAG,
                "$trigger -> verified=${state.verified}, packageState=$level, " +
                    "owner=${state.deviceOwner}, uninstallBlocked=${state.uninstallBlocked}",
            )
        } catch (e: Exception) {
            // Never let a verification failure take the process down.
            Log.e(TAG, "verification failed for trigger=$trigger", e)
        }
    }

    private companion object {
        const val TAG = "RiyPackageWatcher"
    }
}
