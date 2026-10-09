package com.vishal.riy.tracker

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import java.util.TimeZone

/**
 * Receives the morning alarm and the system broadcasts that invalidate its
 * timing, then keeps the reminder correct without ever opening a UI.
 *
 * Deliberately does NOT use an AccessibilityService, does NOT launch an activity
 * from the background, and does NOT use a full-screen intent. Android has
 * restricted all three since Android 10 and the brief forbids abusing them.
 * The reminder's job is to notify; the user opens the app, and the in-app
 * check-in is the reliable path when the notification is missed.
 */
class TrackerReminderReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            TrackerReminder.ACTION_MORNING -> onMorningAlarm(context)

            // The alarm's wall-clock target depends on the local timezone, so any
            // of these invalidates it and it must be re-armed against the new one.
            Intent.ACTION_BOOT_COMPLETED,
            Intent.ACTION_MY_PACKAGE_REPLACED,
            "android.intent.action.TIME_SET",
            Intent.ACTION_TIMEZONE_CHANGED,
            -> {
                Log.i(TAG, "re-arming reminder after ${intent.action}")
                TrackerReminder.rescheduleFromPreferences(context)
            }
        }
    }

    private fun onMorningAlarm(context: Context) {
        val app = context.applicationContext
        val now = System.currentTimeMillis()
        val today = TrackerDates.epochDayOf(now, TimeZone.getDefault())
        val store = TrackerStore(app)

        // Only nudge when there is genuinely something to answer. An answered
        // yesterday must never produce a stale nag.
        val pending = TrackerCheckIn.pendingDate(today) { store.hasRecord(it) }
        if (pending != null) {
            TrackerNotifications.showPending(app, pending)
        }

        // Always re-arm: a one-shot alarm must schedule its successor, otherwise
        // the reminder would silently stop after a single day.
        TrackerReminder.rescheduleFromPreferences(app)
    }

    private companion object {
        const val TAG = "TrackerReminder"
    }
}