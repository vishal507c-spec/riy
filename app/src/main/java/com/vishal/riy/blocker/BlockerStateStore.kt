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

    /**
     * Epoch millis of the last PASSING end-to-end verification. Persisted so the
     * dashboard can report an honest "last verified" time after a restart.
     * 0 means it was never verified.
     */
    var lastVerifiedAt: Long
        get() = prefs.getLong(KEY_LAST_VERIFIED_AT, 0L)
        set(value) = prefs.edit().putLong(KEY_LAST_VERIFIED_AT, value).apply()

    companion object {
        private const val PREFS_NAME = "blocker_state_prefs"
        private const val KEY_PROTECTION_WANTED = "protection_wanted"
        private const val KEY_LAST_VERIFIED_AT = "last_verified_at"
    }
}
