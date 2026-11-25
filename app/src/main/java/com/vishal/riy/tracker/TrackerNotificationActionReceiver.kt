package com.vishal.riy.tracker

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * Handles the YES / NO actions posted on the morning notification.
 *
 * The target date is bound to the PendingIntent via [EXTRA_TARGET_DAY], so a
 * stale notification can never write to the wrong day. Recording is idempotent:
 * an already-answered date is left untouched and the notification is dismissed.
 */
class TrackerNotificationActionReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val targetDay = intent.getLongExtra(EXTRA_TARGET_DAY, Long.MIN_VALUE)
        if (targetDay == Long.MIN_VALUE) return
        val status = when (intent.action) {
            ACTION_RECORD_LEAK -> TrackerStatus.YES
            ACTION_RECORD_NO_LEAK -> TrackerStatus.NO
            else -> null
        } ?: return

        val app = context.applicationContext
        val store = TrackerStore(app)
        // Never overwrite an existing answer from a notification action.
        if (!store.hasRecord(targetDay)) {
            store.save(targetDay, status, System.currentTimeMillis())
        }
        TrackerNotifications.cancel(app)
    }

    companion object {
        const val ACTION_RECORD_LEAK = "com.vishal.riy.tracker.RECORD_LEAK"
        const val ACTION_RECORD_NO_LEAK = "com.vishal.riy.tracker.RECORD_NO_LEAK"
        const val EXTRA_TARGET_DAY = "extra_target_day"
    }
}
