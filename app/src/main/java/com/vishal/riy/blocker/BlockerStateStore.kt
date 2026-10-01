package com.vishal.riy.blocker

import android.content.Context

/**
 * Persists the user's protection choice so that:
 *  - after a reboot [BootReceiver] can restart the service automatically;
 *  - the UI can distinguish "user wants protection" from "service running".
 *
 * This stores INTENT only; actual runtime status always comes from
 * [BlockerState], which the service owns.
 */
class BlockerStateStore(context: Context) {

    private val prefs = context.applicationContext
        .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    /** True when the user's last explicit action was "Enable Protection". */
    fun isProtectionWanted(): Boolean = prefs.getBoolean(KEY_PROTECTION_WANTED, false)

    fun setProtectionWanted(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_PROTECTION_WANTED, enabled).apply()
        // Protection intent changed → coalesced Drive snapshot (never throws).
        com.vishal.riy.drive.DriveSync.requestBackup()
    }

    companion object {
        private const val PREFS_NAME = "blocker_state_prefs"
        private const val KEY_PROTECTION_WANTED = "protection_wanted"
    }
}
