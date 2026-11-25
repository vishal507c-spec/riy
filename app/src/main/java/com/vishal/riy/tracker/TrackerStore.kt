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
    fun save(epochDay: Long, status: TrackerStatus, nowMillis: Long, note: String? = null): TrackerDay {
        val existing = find(epochDay)
        val record = TrackerDay.upsert(epochDay, status, nowMillis, existing, note)
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

    /**
     * Wipes history for one month. Returns how many records were deleted.
     * Explicit user action only.
     */
    fun clearMonth(monthStart: Long, monthLength: Int): Int {
        val monthDays = (0 until monthLength).map { monthStart + it }.toSet()
        val deleted = all().filter { it.epochDay in monthDays }.size
        if (deleted == 0) return 0
        val remaining = all().filterNot { it.epochDay in monthDays }
        prefs.edit().putString(KEY_DAYS, TrackerSerialization.encode(remaining)).apply()
        DriveSync.requestBackup()
        return deleted
    }

    /**
     * Writes [status] for every date in [days], creating or overwriting the
     * per-date record. Used by the month-marking bulk action. Each write is
     * coalesced by Drive Sync exactly like a single save.
     */
    fun bulkSave(days: List<Long>, status: TrackerStatus, nowMillis: Long) {
        if (days.isEmpty()) return
        var merged = all()
        val seen = HashSet<Long>(days.size)
        days.forEach { day ->
            if (seen.add(day)) {
                val record = TrackerDay(epochDay = day, status = status, createdAt = nowMillis, updatedAt = nowMillis)
                merged = merged.filterNot { it.epochDay == day } + record
            }
        }
        prefs.edit().putString(KEY_DAYS, TrackerSerialization.encode(merged)).apply()
        DriveSync.requestBackup()
    }

    /**
     * Attaches or clears a free-text journal note for [epochDay]. The note
     * never affects any count: it is display-only metadata of the day.
     */
    fun saveNote(epochDay: Long, note: String?, nowMillis: Long) {
        val existing = find(epochDay) ?: return
        val updated = existing.copy(note = note?.takeIf { it.isNotBlank() }, updatedAt = nowMillis)
        prefs.edit().putString(KEY_DAYS, TrackerSerialization.encode(all().filterNot { it.epochDay == epochDay } + updated)).apply()
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

    /**
     * Streak freeze: when on, the current NO-run stays visible instead of
     * dropping to zero the day the streak breaks. Purely a display pause for
     * streaks — no answer is invented, and nothing here changes counts.
     */
    var streakFreeze: Boolean
        get() = prefs.getBoolean(KEY_STREAK_FREEZE, false)
        set(value) = prefs.edit().putBoolean(KEY_STREAK_FREEZE, value).apply()

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
        private const val KEY_STREAK_FREEZE = "tracker_streak_freeze"

        const val DEFAULT_REMINDER_HOUR = 9
        const val DEFAULT_REMINDER_MINUTE = 0
    }
}