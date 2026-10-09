package com.vishal.riy.update

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageInstaller
import android.os.Build
import android.util.Log
import androidx.core.content.ContextCompat
import java.io.File
import java.util.concurrent.atomic.AtomicInteger

/**
 * The NORMAL Android install path, built on the modern [PackageInstaller] session
 * API (available on every supported API level) instead of the
 * `ACTION_INSTALL_PACKAGE` intent, which has been deprecated since Android 10 and
 * reports no result at all.
 *
 * What this gives us that the old intent could not:
 *  - a real result. [PackageInstaller.EXTRA_STATUS] comes back with the outcome,
 *    so "installed", "cancelled", "rejected by policy" and "failed" are told
 *    apart instead of guessed;
 *  - [PackageInstaller.STATUS_FAILURE_BLOCKED] identifies the exact "blocked by
 *    administrator" case, which is what routes the user to USB/ADB instead of
 *    back into the dead settings switch.
 *
 * It uses only public APIs and asks for no new permission: the install itself is
 * still authorised by the platform's own "install unknown apps" grant, which is
 * what [InstallEnvironmentProbe] checks before we get here.
 */
object PackageInstallerLauncher {

    private const val TAG = "RiyUpdateInstall"

    /** Explicit action for our own result broadcast. */
    const val ACTION_INSTALL_RESULT = "com.vishal.riy.INSTALL_RESULT"

    private val requestCodes = AtomicInteger(1000)

    /** What the platform reported back about one install attempt. */
    sealed class Outcome {
        /** Installed (or already at that version). */
        data class Success(val statusMessage: String?) : Outcome()

        /** The user dismissed the system installer. */
        data object Cancelled : Outcome()

        /** Policy refused it — the "Blocked by your IT admin" case. */
        data object BlockedByPolicy : Outcome()

        /** Anything else, with the platform's own wording. */
        data class Failed(val status: Int, val statusMessage: String?) : Outcome()
    }

    /**
     * Starts a real install session for [apkFile] and reports the outcome to
     * [onOutcome] exactly once.
     *
     * @return false when a session could not even be created (reported as a
     *         failure rather than swallowed).
     */
    fun install(context: Context, apkFile: File, onOutcome: (Outcome) -> Unit): Boolean {
        val app = context.applicationContext
        if (!apkFile.exists() || apkFile.length() <= 0L) {
            onOutcome(Outcome.Failed(-1, "APK missing"))
            return false
        }

        val installer = app.packageManager.packageInstaller
        var sessionId = -1
        try {
            val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL)
                .apply { setAppPackageName(app.packageName) }
            sessionId = installer.createSession(params)
            val results = AtomicReferenceHolder(onOutcome)

            val receiver = ResultReceiver(
                app,
                installer,
                sessionId,
                results,
            )
            ContextCompat.registerReceiver(
                app,
                receiver,
                IntentFilter(ACTION_INSTALL_RESULT),
                ContextCompat.RECEIVER_NOT_EXPORTED,
            )

            val pendingIntent = pendingResultIntent(app, receiver, sessionId)

            installer.openSession(sessionId).use { session ->
                session.openWrite("riy", 0, apkFile.length()).use { out ->
                    apkFile.inputStream().use { input ->
                        val buffer = ByteArray(64 * 1024)
                        while (true) {
                            val read = input.read(buffer)
                            if (read == -1) break
                            out.write(buffer, 0, read)
                        }
                    }
                    session.fsync(out)
                }
                // From here the platform drives the UI; our receiver hears the result.
                session.commit(pendingIntent.intentSender)
            }
            return true
        } catch (e: Exception) {
            Log.w(TAG, "install session failed: ${e.javaClass.simpleName}: ${e.message}")
            if (sessionId >= 0) {
                runCatching { installer.abandonSession(sessionId) }
            }
            onOutcome(Outcome.Failed(-1, e.message))
            return false
        }
    }

    /** Maps the platform status constant onto our single [Outcome] type. */
    fun outcomeFor(status: Int, statusMessage: String?): Outcome = when (status) {
        PackageInstaller.STATUS_SUCCESS -> Outcome.Success(statusMessage)
        PackageInstaller.STATUS_FAILURE_BLOCKED -> Outcome.BlockedByPolicy
        PackageInstaller.STATUS_FAILURE_ABORTED -> Outcome.Cancelled
        else -> Outcome.Failed(status, statusMessage)
    }

    /**
     * A MUTABLE PendingIntent is mandatory here: the platform fills in the result
     * extras, so the receiver must be allowed to add them. It is targeted at this
     * app's own explicit broadcast action and carries no exported component.
     */
    private fun pendingResultIntent(
        context: Context,
        receiver: BroadcastReceiver,
        sessionId: Int,
    ): PendingIntent {
        val intent = Intent(ACTION_INSTALL_RESULT).setPackage(context.packageName)
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) PendingIntent.FLAG_MUTABLE else 0
        return PendingIntent.getBroadcast(
            context,
            requestCodes.incrementAndGet(),
            intent,
            flags,
        )
    }

    /** Receives exactly one install result and then unregisters itself. */
    private class ResultReceiver(
        private val context: Context,
        private val installer: PackageInstaller,
        private val sessionId: Int,
        private val holder: AtomicReferenceHolder,
    ) : BroadcastReceiver() {

        override fun onReceive(context: Context?, intent: Intent?) {
            val status = intent?.getIntExtra(
                PackageInstaller.EXTRA_STATUS,
                PackageInstaller.STATUS_FAILURE_ABORTED,
            ) ?: PackageInstaller.STATUS_FAILURE_ABORTED
            val message = intent?.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE)
            Log.i(TAG, "install result status=$status")
            runCatching { installer.abandonSession(sessionId) }
            runCatching { context?.unregisterReceiver(this) }
            holder.deliver(outcomeFor(status, message))
        }
    }

    /** Tiny holder so the receiver can deliver exactly once, from any thread. */
    private class AtomicReferenceHolder(private val callback: (Outcome) -> Unit) {
        private val done = java.util.concurrent.atomic.AtomicBoolean(false)
        fun deliver(outcome: Outcome) {
            if (done.compareAndSet(false, true)) callback(outcome)
        }
    }
}