package com.vishal.riy.protection.recovery

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import com.vishal.riy.protection.integrity.ProtectionReconciler

/**
 * The missing production trigger for restriction expiry.
 *
 * The 2-hour deadline already lives in `LockStore`; what did not exist was any
 * code that ACTS when it passes while the app stays open. This schedules one
 * system-owned alarm at that exact deadline. The receiver then walks the same
 * [ProtectionReconciler] used by boot, foreground and package events — it does
 * not compute a second clock and does not own a competing deadline.
 */
object RestrictionExpiryScheduler {

    const val ACTION_EXPIRED = "com.vishal.riy.RESTRICTION_EXPIRED"

    private const val TAG = "RiyExpiry"
    private const val REQUEST_CODE = 7712

    /** Arms (or re-arms) the single expiry alarm for [deadlineMillis]. */
    fun schedule(context: Context, deadlineMillis: Long) {
        val app = context.applicationContext
        val manager = app.getSystemService(Context.ALARM_SERVICE) as? AlarmManager ?: return
        runCatching {
            manager.cancel(pendingIntent(app))
            // A past deadline needs no alarm: the caller's normal reconciliation
            // pass already handles it. Scheduling it here would recurse.
            if (deadlineMillis <= System.currentTimeMillis()) return
            manager.setAndAllowWhileIdle(
                AlarmManager.RTC_WAKEUP,
                deadlineMillis,
                pendingIntent(app),
            )
            Log.i(TAG, "restriction expiry armed at $deadlineMillis")
        }.onFailure { Log.w(TAG, "could not arm expiry: ${it.message}") }
    }

    fun cancel(context: Context) {
        val manager = context.applicationContext
            .getSystemService(Context.ALARM_SERVICE) as? AlarmManager ?: return
        runCatching { manager.cancel(pendingIntent(context.applicationContext)) }
    }

    private fun pendingIntent(context: Context): PendingIntent {
        val intent = Intent(context, RestrictionExpiryReceiver::class.java)
            .setAction(ACTION_EXPIRED)
        var flags = PendingIntent.FLAG_UPDATE_CURRENT
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            flags = flags or PendingIntent.FLAG_IMMUTABLE
        }
        return PendingIntent.getBroadcast(context, REQUEST_CODE, intent, flags)
    }
}

/** Receives the system alarm and reconciles the expired session. */
class RestrictionExpiryReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != RestrictionExpiryScheduler.ACTION_EXPIRED) return
        ProtectionReconciler.reconcileAll(
            context.applicationContext,
            "restriction_expired",
        )
    }
}
