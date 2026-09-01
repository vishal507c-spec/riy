package com.vishal.riy.blocker

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

/**
 * Restores protection after a device reboot. Only restarts the VPN service
 * when the user's persisted choice was ON; otherwise does nothing.
 */
class BootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return
        val wanted = BlockerStateStore(context).isProtectionWanted()
        Log.i(TAG, "boot completed; protectionWanted=$wanted")
        if (!wanted) return
        try {
            // Boot timing race: the system VPN state may not be ready yet, so
            // the service retries establish with backoff (allowRetry=true).
            BlockerVpnService.start(context, allowRetry = true)
        } catch (e: Exception) {
            // Starting a foreground service from BOOT_COMPLETED can throw on
            // some OEMs; never crash the system broadcast.
            Log.e(TAG, "failed to start service on boot", e)
        }
    }

    private companion object {
        const val TAG = "BlockerBoot"
    }
}
