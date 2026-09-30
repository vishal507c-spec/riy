package com.vishal.riy.update

import android.content.Context

/**
 * Throttles update checks so app startups stay fast and the unauthenticated
 * GitHub API rate limit (60 req/hour/device) is respected. One lightweight
 * check per hour at most; "Later" simply defers to the next qualifying
 * lifecycle event.
 */
class UpdatePreferences(context: Context) {

    private val prefs = context.applicationContext
        .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun shouldCheckNow(nowMs: Long = System.currentTimeMillis()): Boolean =
        nowMs - prefs.getLong(KEY_LAST_CHECK, 0L) >= CHECK_INTERVAL_MS

    fun markChecked(nowMs: Long = System.currentTimeMillis()) {
        prefs.edit().putLong(KEY_LAST_CHECK, nowMs).apply()
    }

    companion object {
        private const val PREFS_NAME = "app_update_prefs"
        private const val KEY_LAST_CHECK = "last_check_ms"
        private const val CHECK_INTERVAL_MS = 60L * 1000L // 1 minute
    }
}
