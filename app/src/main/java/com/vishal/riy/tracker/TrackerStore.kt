package com.vishal.riy.tracker

import android.content.Context
import com.vishal.riy.drive.DriveSync

/**
 * On-device store for the daily tracker.
 *
 * REUSES the existing infrastructure rather than adding anything:
 *  - SharedPreferences, MODE_PRIVATE, exactly like every other RIY store, so
 *    records stay in app-private storage and are never readable by other apps;
 *  - [DriveSync.requestBackup] after every write, exactly like
 *    `PrefsLockStore` / `PrefsProtectionStateStore`, so the records ride along
 *    in the EXISTING Drive snapshot. No second backup system, no second
 *    database, no second login.
 *
 * The prefs file name must stay in sync with
 * `com.vishal.riy.drive.RiySnapshot.STORE_FILES`, which is what makes the
 * records part of the backup payload.
 */
class TrackerStore(context: Context) {

    private val prefs = context.applicationContext
        .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    /** All records, ordered by date. Malformed rows are skipped, not fatal. */
    fun all(): List<TrackerDay> = TrackerSerialization.decode(prefs.getString(KEY_DAYS, null))

    /** The record for one calendar date, or null when unrecorded. */
    fun find(epochDay: Long): TrackerDay? = all().firstOrNull { it.epochDay == epochDay }

    fun hasRecord(epochDay: Long): Boolean = find(epochDay) != null

    /** True when the user has ever answered. Guards the empty dashboard state. */
    fun isStarted(): Boolean = prefs.contains(KEY_DAYS) && all().isNotEmpty()

    /**
     * Creates or updates the single record for [epochDay].
     *
     * Saving the same date twice updates that one row: several events on one
     * day can never inflate the YES count or create a duplicate backup row.
     * Returns the stored record.
     */
    fun save(epochDay: Long, status: TrackerStatus, nowMillis: Long): TrackerDay {
        val existing = find(epochDay)
        val record = TrackerDay.upsert(epochDay, status, nowMillis, existing)
        val merged = all().filterNot { it.epochDay == epochDay } + record
        prefs.edit().putString(KEY_DAYS, TrackerSerialization.encode(merged)).apply()
        // Tracker history changed → coalesced Drive snapshot (never throws,
        // offline-safe: the existing manager queues it and retries).
        DriveSync.requestBackup()
        return record
    }

    /** Removes one date. Never called implicitly; deletion is always explicit. */
    fun delete(epochDay: Long) {
        val remaining = all().filterNot { it.epochDay == epochDay }
        prefs.edit().putString(KEY_DAYS, TrackerSerialization.encode(remaining)).apply()
        DriveSync.requestBackup()
    }

    /** Wipes all history. Explicit user action only. */
    fun clear() {
        prefs.edit().remove(KEY_DAYS).apply()
        DriveSync.requestBackup()
    }

    // ------------------------------------------------------------- reminders

    /** True once the user has been shown the automatic prompt on [epochDay]. */
    fun wasPromptedOn(epochDay: Long): Boolean =
        prefs.getLong(KEY_LAST_PROMPT_DAY, Long.MIN_VALUE) == epochDay

    /** Records that the automatic prompt was shown, so it is shown once a day. */
    fun markPrompted(epochDay: Long) {
        prefs.edit().putLong(KEY_LAST_PROMPT_DAY, epochDay).apply()
    }

    var reminderEnabled: Boolean
        get() = prefs.getBoolean(KEY_REMINDER_ENABLED, true)
        set(value) = prefs.edit().putBoolean(KEY_REMINDER_ENABLED, value).apply()

    var reminderHour: Int
        get() = prefs.getInt(KEY_REMINDER_HOUR, DEFAULT_REMINDER_HOUR)
        set(value) = prefs.edit().putInt(KEY_REMINDER_HOUR, value.coerceIn(0, 23)).apply()

    var reminderMinute: Int
        get() = prefs.getInt(KEY_REMINDER_MINUTE, DEFAULT_REMINDER_MINUTE)
        set(value) = prefs.edit().putInt(KEY_REMINDER_MINUTE, value.coerceIn(0, 59)).apply()

    companion object {
        /**
         * MUST match an entry in `RiySnapshot.STORE_FILES`. Appended last there
         * to keep the canonical (alphabetical) snapshot byte order stable.
         */
        const val PREFS_NAME = "riy_tracker_prefs"

        const val KEY_DAYS = "tracker_days_v1"
        private const val KEY_LAST_PROMPT_DAY = "tracker_last_prompt_day"
        private const val KEY_REMINDER_ENABLED = "tracker_reminder_enabled"
        private const val KEY_REMINDER_HOUR = "tracker_reminder_hour"
        private const val KEY_REMINDER_MINUTE = "tracker_reminder_minute"

        const val DEFAULT_REMINDER_HOUR = 9
        const val DEFAULT_REMINDER_MINUTE = 0
    }
}