package com.vishal.riy.tracker

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.util.Log
import java.util.Calendar
import java.util.Locale
import java.util.TimeZone

/**
 * Morning reminder scheduling, built ONLY on supported Android APIs.
 *
 * Design constraints, and why each matters:
 *
 *  - [AlarmManager.setAndAllowWhileIdle] rather than an exact alarm. Exact
 *    alarms need SCHEDULE_EXACT_ALARM/USE_EXACT_ALARM, which this app does not
 *    hold and should not add: a "did you masturbate" nag at 09:00 does not
 *    justify a permission that lets the app wake the device on demand.
 *  - It is ONE-SHOT and re-armed by [TrackerReminderReceiver]. A repeating alarm
 *    cannot follow a timezone or DST change (its interval is fixed wall-clock
 *    time), so re-arming on each fire keeps the reminder at the user's chosen
 *    LOCAL time all year.
 *  - No activity is ever launched from the background, no full-screen intent is
 *    used, and unlock is never intercepted. Android would block that anyway
 *    since Android 10, and the brief forbids misusing it.
 */
object TrackerReminder {

    private const val TAG = "TrackerReminder"

    /** Must be stable: it is part of the PendingIntent identity. */
    const val ACTION_MORNING = "com.vishal.riy.tracker.MORNING_CHECK_IN"

    const val NOTIFICATION_CHANNEL_ID = "tracker_checkin"
    const val NOTIFICATION_ID = 2001

    /** The reminder's PendingIntent. */
    fun pendingIntent(context: Context): PendingIntent {
        val intent = Intent(context, TrackerReminderReceiver::class.java).apply {
            action = ACTION_MORNING
            // Distinct data URI keeps this PendingIntent from colliding with any
            // other request code in the app.
            data = android.net.Uri.parse("riy://tracker/morning")
        }
        var flags = PendingIntent.FLAG_UPDATE_CURRENT
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.M) {
            flags = flags or PendingIntent.FLAG_IMMUTABLE
        }
        return PendingIntent.getBroadcast(context, REQUEST_CODE, intent, flags)
    }

    /**
     * Arms the next morning reminder. Idempotent and never throws: a missing
     * reminder degrades to "use the app in the morning", it never breaks
     * anything.
     */
    fun schedule(context: Context, hour: Int, minute: Int) {
        val manager = context.getSystemService(Context.ALARM_SERVICE) as? AlarmManager ?: return
        runCatching {
            val triggerAt = nextOccurrenceMillis(hour, minute, System.currentTimeMillis())
            manager.cancel(pendingIntent(context))
            manager.setAndAllowWhileIdle(
                AlarmManager.RTC_WAKEUP,
                triggerAt,
                pendingIntent(context),
            )
            Log.i(TAG, "morning reminder armed at $triggerAt")
        }.onFailure { Log.w(TAG, "could not arm reminder: ${it.message}") }
    }

    fun cancel(context: Context) {
        val manager = context.getSystemService(Context.ALARM_SERVICE) as? AlarmManager ?: return
        runCatching { manager.cancel(pendingIntent(context)) }
    }

    /**
     * Applies the stored preference: arms or cancels, and is safe to call from
     * every entry point (app start, boot, timezone change).
     */
    fun rescheduleFromPreferences(context: Context) {
        val store = TrackerStore(context)
        if (!store.reminderEnabled) {
            cancel(context)
        } else {
            schedule(context, store.reminderHour, store.reminderMinute)
        }
    }

    /**
     * Next occurrence of [hour]:[minute] local, strictly in the future.
     *
     * Pure w.r.t. its arguments so it is directly unit-testable.
     */
    fun nextOccurrenceMillis(
        hour: Int,
        minute: Int,
        nowMillis: Long,
        tz: TimeZone = TimeZone.getDefault(),
    ): Long {
        val calendar = Calendar.getInstance(tz, Locale.US).apply {
            timeInMillis = nowMillis
            set(Calendar.HOUR_OF_DAY, hour)
            set(Calendar.MINUTE, minute)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }
        // Calendar.set() re-normalises, which is exactly what makes this correct
        // on a DST "spring forward" day where 02:30 does not exist.
        if (calendar.timeInMillis <= nowMillis) {
            calendar.add(Calendar.DAY_OF_YEAR, 1)
        }
        return calendar.timeInMillis
    }

    private const val REQUEST_CODE = 2001
}