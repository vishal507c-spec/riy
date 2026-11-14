package com.vishal.riy.blocker

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

/**
 * Restores protection after a device reboot or an in-place self-update. Only
 * restarts the VPN service when the user's persisted choice was ON; otherwise
 * it makes sure no stale always-on binding or watchdog can resurrect it.
 */
class BootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action
        if (action != Intent.ACTION_BOOT_COMPLETED &&
            action != Intent.ACTION_LOCKED_BOOT_COMPLETED &&
            action != Intent.ACTION_MY_PACKAGE_REPLACED
        ) {
            return
        }
        val wanted = BlockerStateStore(context).isProtectionWanted()
        Log.i(TAG, "$action; protectionWanted=$wanted")

        // Start the filter FIRST. Full reconciliation can take seconds, and
        // this OEM grants only a short background-start window after boot;
        // the previous order (reconcile, then start) lost that window and the
        // VPN never came back until the user opened the app.
        if (wanted) {
            // Device Owner always-on VPN is what makes the SYSTEM restart the
            // filter after reboot, task removal and process death. Idempotent;
            // safe no-op when RIY is not the owner.
            com.vishal.riy.admin.AlwaysOnVpn.apply(context)
            try {
                BlockerVpnService.start(context, allowRetry = true)
            } catch (e: Exception) {
                // Starting a foreground service from a boot broadcast can throw
                // on some OEMs; never crash the system broadcast.
                Log.e(TAG, "failed to start service on $action", e)
            }
            // Fallback for ROMs that ignore always-on VPN or block the start
            // above: the alarm is held by the system and re-arms itself.
            ProtectionWatchdog.schedule(context)
        } else {
            // Protection is explicitly OFF: remove any binding that could
            // otherwise start it behind the user's back.
            com.vishal.riy.admin.AlwaysOnVpn.clear(context)
            ProtectionWatchdog.cancel(context)
        }

        // Recovery must run even when the VPN cannot start yet: a live
        // restriction is re-applied (or an expired one cleared) and bypass
        // packages are re-neutralized through the single reconciler.
        try {
            com.vishal.riy.protection.integrity.ProtectionReconciler.reconcileAll(
                context,
                if (action == Intent.ACTION_MY_PACKAGE_REPLACED) "package_replaced" else "boot_completed",
            )
        } catch (e: Exception) {
            Log.e(TAG, "boot reconciliation failed", e)
        }
    }

    private companion object {
        const val TAG = "BlockerBoot"
    }
}
