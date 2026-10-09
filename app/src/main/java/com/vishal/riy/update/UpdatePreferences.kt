package com.vishal.riy.update

import android.content.Context

/**
 * Throttles update checks so app startups stay fast and the unauthenticated
 * GitHub API rate limit (60 req/hour/device) is respected. One lightweight
 * check per hour at most; "Later" simply defers to the next qualifying
 * lifecycle event.
 *
 * It also stores the ONE piece of state that makes the "Update Now → permission
 * → Settings → blocked → back → same dialog" loop impossible: whether the
 * "install unknown apps" screen has already been tried. Once it has been tried
 * and did not help, the app never sends the user there again and always offers
 * the USB/ADB path instead.
 */
class UpdatePreferences(context: Context) {

    private val prefs = context.applicationContext
        .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun shouldCheckNow(nowMs: Long = System.currentTimeMillis()): Boolean =
        nowMs - prefs.getLong(KEY_LAST_CHECK, 0L) >= CHECK_INTERVAL_MS

    fun markChecked(nowMs: Long = System.currentTimeMillis()) {
        prefs.edit().putLong(KEY_LAST_CHECK, nowMs).apply()
    }

    /** True once the user has been sent to the unknown-sources settings screen. */
    fun hasPromptedForInstallGrant(): Boolean = prefs.getBoolean(KEY_PROMPTED_GRANT, false)

    /**
     * Records that the settings screen was shown. This is what breaks the loop:
     * [InstallRouteDecider] refuses to offer that screen a second time.
     */
    fun markPromptedForInstallGrant() {
        prefs.edit().putBoolean(KEY_PROMPTED_GRANT, true).apply()
    }

    /**
     * Remembers that this device refuses direct installs, so the update dialog
     * goes straight to the USB screen on every future attempt instead of
     * re-exploring the system installer each time.
     */
    fun isDirectInstallRefused(): Boolean = prefs.getBoolean(KEY_DIRECT_REFUSED, false)

    fun markDirectInstallRefused() {
        prefs.edit().putBoolean(KEY_DIRECT_REFUSED, true).apply()
    }

    /**
     * True once the PLATFORM itself has refused an install with
     * `STATUS_FAILURE_BLOCKED` (the "Blocked by your IT admin" answer). That is
     * the only public, authoritative signal that the unknown-sources switch is
     * owned by an administrator, so we act on exactly that and never guess.
     */
    fun isInstallBlockedObserved(): Boolean = prefs.getBoolean(KEY_INSTALL_BLOCKED, false)

    fun markInstallBlockedObserved() {
        prefs.edit().putBoolean(KEY_INSTALL_BLOCKED, true).apply()
    }

    companion object {
        private const val PREFS_NAME = "app_update_prefs"
        private const val KEY_LAST_CHECK = "last_check_ms"
        private const val KEY_PROMPTED_GRANT = "prompted_for_install_grant"
        private const val KEY_DIRECT_REFUSED = "direct_install_refused"
        private const val KEY_INSTALL_BLOCKED = "install_blocked_observed"
        private const val CHECK_INTERVAL_MS = 60L * 1000L // 1 minute
    }
}
