package com.vishal.riy.protection.integrity

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

/**
 * Lightweight package-state watchdog. It does NOT poll: it wakes only on real
 * Android package lifecycle broadcasts directed at RIY, which is exactly the
 * set of events an OEM freezer produces when it actuates on a package.
 *
 * On each event it re-verifies the real state and records an integrity result.
 * It never crashes the app and never attempts to reinstall or download
 * anything — if RIY is genuinely missing, that fact is recorded, not hidden.
 */
class PackageStateWatcher : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val pkg = intent.data?.schemeSpecificPart
        if (pkg != context.packageName) return

        when (intent.action) {
            Intent.ACTION_PACKAGE_CHANGED,
            Intent.ACTION_PACKAGE_REPLACED,
            -> verify("package_changed", context)

            Intent.ACTION_PACKAGE_REMOVED -> {
                // Replacing an update sends REMOVED first; a genuine removal of
                // a Device Owner package is blocked by the framework, so this
                // path should never succeed. Verify rather than assume.
                verify("package_removed", context)
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
