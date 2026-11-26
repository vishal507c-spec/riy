package com.vishal.riy.blocker

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Log

/**
 * A small, battery-cheap safety net for the cases the system does not cover:
 * an OEM task killer that removes the process, a missed boot broadcast, or a
 * transient VPN revocation.
 *
 * It is deliberately NOT a polling loop and owns no state. It is a one-shot
 * inexact alarm (`setAndAllowWhileIdle`, so no exact-alarm permission is
 * needed); [ProtectionWatchdogReceiver] re-arms it on every fire. The persisted
 * [BlockerStateStore.isProtectionWanted] flag remains the only authority: when
 * it is OFF the alarm is cancelled and the receiver does nothing.
 *
 * The primary persistence mechanism is [com.vishal.riy.admin.AlwaysOnVpn]; this
 * watchdog is the fallback for devices where Device Owner always-on VPN is
 * unavailable or was cleared.
 */
object ProtectionWatchdog {

    private const val TAG = "RiyWatchdog"

    /** Explicit action; the receiver is not exported, so only RIY can send it. */
    const val ACTION_CHECK = "com.vishal.riy.blocker.WATCHDOG_CHECK"

    private const val REQUEST_CODE = 4101

    /**
     * Background fallback cadence while protection is wanted. The fast path
     * (task removal) is a 5-second alarm; this one only covers process deaths
     * that happen without a task callback. AlarmManager may defer it in Doze,
     * which is fine: with the screen off there is nothing to browse.
     */
    private const val INTERVAL_MS = 5 * 60 * 1000L
    private const val SOON_DELAY_MS = 5_000L

    /** Arms the next health check. Idempotent and never throws. */
    fun schedule(context: Context) = scheduleIn(context, INTERVAL_MS)

    /**
     * Arms a check a few seconds from now. Used from `onTaskRemoved`, where the
     * OEM may kill this process immediately afterwards; AlarmManager lives in
     * the system process, so the request survives us.
     */
    fun scheduleSoon(context: Context) = scheduleIn(context, SOON_DELAY_MS)

    fun cancel(context: Context) {
        val manager = context.getSystemService(Context.ALARM_SERVICE) as? AlarmManager ?: return
        runCatching { manager.cancel(pendingIntent(context)) }
    }

    private fun scheduleIn(context: Context, delayMs: Long) {
        val manager = context.getSystemService(Context.ALARM_SERVICE) as? AlarmManager ?: return
        runCatching {
            manager.cancel(pendingIntent(context))
            manager.setAndAllowWhileIdle(
                AlarmManager.RTC_WAKEUP,
                System.currentTimeMillis() + delayMs,
                pendingIntent(context),
            )
            Log.i(TAG, "watchdog armed in ${delayMs / 1000}s")
        }.onFailure { Log.w(TAG, "could not arm watchdog: ${it.message}") }
    }

    private fun pendingIntent(context: Context): PendingIntent {
        val intent = Intent(context, ProtectionWatchdogReceiver::class.java).apply {
            action = ACTION_CHECK
            // Distinct data URI so this PendingIntent cannot collide with the
            // tracker reminder or the update installer.
            data = Uri.parse("riy://watchdog/protection")
        }
        var flags = PendingIntent.FLAG_UPDATE_CURRENT
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.M) {
            flags = flags or PendingIntent.FLAG_IMMUTABLE
        }
        return PendingIntent.getBroadcast(context, REQUEST_CODE, intent, flags)
    }
}

/**
 * Re-arms the watchdog and, when the user still wants protection, asks the
 * service to resume. Every step is best-effort: a rejected background start
 * only delays the next attempt, it never crashes the system broadcast.
 */
class ProtectionWatchdogReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ProtectionWatchdog.ACTION_CHECK) return
        // Re-arm FIRST. If the start below fails (OEM restriction, no VPN
        // consent, boot race), the watchdog must still be alive for the next
        // attempt.
        ProtectionWatchdog.schedule(context)

        if (!BlockerStateStore(context).isProtectionWanted()) {
            Log.i(TAG, "protection not wanted; watchdog idle")
            ProtectionWatchdog.cancel(context)
            return
        }

        // Re-assert the Device Owner binding on every check: this also repairs
        // a policy that an OEM update or another admin cleared.
        com.vishal.riy.admin.AlwaysOnVpn.apply(context)

        try {
            // Re-VERIFY, not just "start": a running filter with a stale
            // unverified result used to stay that way forever because start()
            // is a no-op while the tun is up.
            BlockerVpnService.verify(context)
        } catch (e: Exception) {
            // Background-start restrictions or a boot race: the alarm armed
            // above will try again, so this is never fatal.
            Log.w(TAG, "watchdog could not start protection: ${e.message}")
        }
    }

    private companion object {
        const val TAG = "RiyWatchdog"
    }
}
