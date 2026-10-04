package com.vishal.riy.guard

import android.content.Context
import com.vishal.riy.guard.rules.WhatsAppStatusRule

/**
 * The single place the user's per-target UI-guard choices are persisted.
 *
 * Mirrors [com.vishal.riy.blocker.BlockerStateStore]: it stores INTENT only, and
 * it is deliberately NOT part of [com.vishal.riy.drive.RiySnapshot]'s backup set
 * — enabling an on-device guard is a local choice, never uploaded or restored.
 */
class UiGuardStore(context: Context) {

    private val prefs = context.applicationContext
        .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    /** True when the user left this target's guard switched on. */
    fun isEnabled(targetId: String): Boolean =
        prefs.getBoolean(key(targetId), defaultEnabled(targetId))

    /** Persists the user's choice for one target only. */
    fun setEnabled(targetId: String, enabled: Boolean) {
        prefs.edit().putBoolean(key(targetId), enabled).apply()
    }

    private fun key(targetId: String) = "target_enabled:$targetId"

    companion object {
        private const val PREFS_NAME = "ui_guard_prefs"

        /**
         * Defaults are deliberately per target so a NEW target can ship
         * disabled if that is ever the safer choice. WhatsApp Status blocking
         * ships ON: it is the feature the guard exists for, and until
         * accessibility access is granted it is inert anyway.
         */
        fun defaultEnabled(targetId: String): Boolean = when (targetId) {
            WhatsAppStatusRule.TARGET_ID -> true
            else -> false
        }
    }
}
