package com.vishal.riy.tracker

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.vishal.riy.MainActivity
import com.vishal.riy.R

/**
 * The morning notification.
 *
 * PRIVACY (hard rule for this feature): the notification says ONLY
 * "RIY Shield — Daily check-in pending." It never contains a date, an answer, a
 * streak, or any other detail about what is being tracked. A lock-screen
 * notification is readable by anyone holding the phone, so the text has to be
 * meaningless without opening the app.
 *
 * Tapping it opens the app straight into the check-in screen. It never uses a
 * full-screen intent and never launches an activity on its own from the
 * background.
 */
object TrackerNotifications {

    private const val TAG = "TrackerNotify"

    fun ensureChannel(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val channel = NotificationChannel(
            TrackerReminder.NOTIFICATION_CHANNEL_ID,
            context.getString(R.string.tracker_notification_channel),
            NotificationManager.IMPORTANCE_DEFAULT,
        ).apply {
            description = context.getString(R.string.tracker_notification_channel_desc)
            setShowBadge(true)
        }
        context.getSystemService(NotificationManager::class.java)
            ?.createNotificationChannel(channel)
    }

    /**
     * Posts the pending-check-in notification, if permitted. Silently does
     * nothing when the user has not granted POST_NOTIFICATIONS — the in-app
     * check-in remains the reliable path and must not depend on this.
     */
    fun showPending(context: Context, pendingDay: Long) {
        ensureChannel(context)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            Log.i(TAG, "notification permission not granted; skipping")
            return
        }
        runCatching {
            val notification = NotificationCompat.Builder(context, TrackerReminder.NOTIFICATION_CHANNEL_ID)
                // Deliberately generic: no date, no answer, nothing sensitive.
                .setSmallIcon(R.drawable.ic_launcher)
                .setContentTitle(context.getString(R.string.tracker_notification_title))
                .setContentText(context.getString(R.string.tracker_notification_text))
                .setStyle(NotificationCompat.BigTextStyle().bigText(
                    context.getString(R.string.tracker_notification_text),
                ))
                .setContentIntent(checkInIntent(context))
                .setAutoCancel(true)
                .setPriority(NotificationCompat.PRIORITY_DEFAULT)
                .setCategory(NotificationCompat.CATEGORY_REMINDER)
                .build()
            NotificationManagerCompat.from(context)
                .notify(TrackerReminder.NOTIFICATION_ID, notification)
        }.onFailure { Log.w(TAG, "could not post reminder: ${it.message}") }
    }

    fun cancel(context: Context) {
        runCatching {
            NotificationManagerCompat.from(context).cancel(TrackerReminder.NOTIFICATION_ID)
        }
    }

    /** Intent that opens MainActivity directly on the check-in screen. */
    private fun checkInIntent(context: Context): PendingIntent {
        val intent = Intent(context, MainActivity::class.java).apply {
            action = Intent.ACTION_MAIN
            addCategory(Intent.CATEGORY_LAUNCHER)
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra(MainActivity.EXTRA_OPEN_CHECK_IN, true)
        }
        var flags = PendingIntent.FLAG_UPDATE_CURRENT
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            flags = flags or PendingIntent.FLAG_IMMUTABLE
        }
        return PendingIntent.getActivity(context, TrackerReminder.NOTIFICATION_ID, intent, flags)
    }
}