package com.vishal.riy

import android.os.Bundle
import android.content.Intent
import android.util.Log
import androidx.activity.compose.setContent
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.lifecycleScope
import com.vishal.riy.blocker.BlockerState
import com.vishal.riy.blocker.BlockerStateStore
import com.vishal.riy.blocker.BlockerVpnService
import com.vishal.riy.ui.RiyApp
import com.vishal.riy.update.ReleaseInfo
import com.vishal.riy.update.UpdateChecker
import com.vishal.riy.update.UpdateDialogFragment
import com.vishal.riy.update.UpdatePreferences
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

/** TEMPORARY diagnostic tag; never contains credentials. */
private const val LOG_TAG = "RiyUpdate"

/**
 * App entry point. Jetpack Compose renders the protection UI; the only other
 * feature-specific behaviour is the asynchronous update check fired on
 * startup (unchanged): it runs on Dispatchers.IO, never blocks rendering,
 * and silently no-ops on any failure.
 */
class MainActivity : AppCompatActivity() {

    private lateinit var updatePreferences: UpdatePreferences

    // Pending update + lifecycle-safe dialog showing (see showUpdateDialogIfReady).
    private var pendingUpdate: ReleaseInfo? = null
    private var pendingInstalledCode: Long = 0L
    private var updateDialogShown = false
    private var resumeObserver: LifecycleEventObserver? = null

    /**
     * http/https URL the system asked riy to open (default-browser intent).
     * Cleared once the browser has consumed it. Observed by [RiyApp].
     */
    private var externalUrl by mutableStateOf<String?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        externalUrl = intent?.dataString?.takeIf { it.isNotBlank() }
        setContent {
            RiyApp(
                externalUrl = externalUrl,
                onExternalUrlHandled = { externalUrl = null },
            )
        }
        updatePreferences = UpdatePreferences(this)
        scheduleUpdateCheck()
        restoreProtectionIfInterrupted()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        // A link tap while riy is already open: hand it to the browser.
        externalUrl = intent.dataString?.takeIf { it.isNotBlank() }
    }

    override fun onStart() {
        super.onStart()
        restoreProtectionIfInterrupted()
    }

    /**
     * Self-heal: if the user wants protection but the service is not running
     * (process death, update kill, missed boot broadcast), restart it. This
     * never overrides a real OFF/FAILED reported by the service itself.
     */
    private fun restoreProtectionIfInterrupted() {
        val phase = BlockerState.current().phase
        if (BlockerStateStore(this).isProtectionWanted() &&
            phase != BlockerState.Phase.CONNECTED &&
            phase != BlockerState.Phase.CONNECTING
        ) {
            Log.i("BlockerApp", "protection wanted but $phase — restarting service")
            try {
                BlockerVpnService.start(this)
            } catch (e: Exception) {
                Log.e("BlockerApp", "failed to restart protection", e)
            }
        }
    }

    private fun scheduleUpdateCheck() {
        val canCheck = updatePreferences.shouldCheckNow()
        Log.i(LOG_TAG, "update check started; shouldCheckNow=$canCheck")
        if (!canCheck) return
        lifecycleScope.launch {
            val installedCode = currentVersionCode()
            Log.i(LOG_TAG, "installedVersionCode=$installedCode")
            val info = try {
                UpdateChecker.checkForUpdate(installedCode)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e(LOG_TAG, "update check threw: ${e.javaClass.simpleName}: ${e.message}", e)
                null // never let update checking break the app
            }
            updatePreferences.markChecked()
            val update = info ?: run {
                Log.w(LOG_TAG, "no update available; dialog will not be shown")
                return@launch
            }
            Log.i(LOG_TAG, "update available v${update.versionName} (code ${update.versionCode}); dialog requested")
            pendingUpdate = update
            pendingInstalledCode = installedCode
            showUpdateDialogIfReady()
        }
    }

    /**
     * Shows the update dialog only when the Activity is in a valid lifecycle state
     * (STARTED or better) AND [supportFragmentManager] has not saved its state —
     * otherwise `DialogFragment.show()` would throw
     * "Can not perform this action after onSaveInstanceState". If the Activity is
     * not ready yet (e.g. it saved state while the async check was running), the
     * dialog is deferred until the Activity resumes. [updateDialogShown] prevents
     * the dialog from ever being shown more than once.
     */
    private fun showUpdateDialogIfReady() {
        val update = pendingUpdate ?: return
        if (updateDialogShown) return

        if (supportFragmentManager.isStateSaved ||
            !lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)
        ) {
            // Not safe to commit a transaction right now; retry on the next ON_RESUME.
            registerResumeObserver()
            return
        }

        updateDialogShown = true
        pendingUpdate = null
        removeResumeObserver()
        Log.i(LOG_TAG, "showing new update dialog (v${update.versionName})")
        UpdateDialogFragment.newInstance(update, pendingInstalledCode)
            .show(supportFragmentManager, UpdateDialogFragment::class.java.simpleName)
    }

    private fun registerResumeObserver() {
        if (resumeObserver != null) return
        resumeObserver = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) showUpdateDialogIfReady()
        }
        lifecycle.addObserver(resumeObserver!!)
    }

    private fun removeResumeObserver() {
        resumeObserver?.let { runCatching { lifecycle.removeObserver(it) } }
        resumeObserver = null
    }

    override fun onDestroy() {
        removeResumeObserver()
        super.onDestroy()
    }

    private fun currentVersionCode(): Long = try {
        val pm = packageManager.getPackageInfo(packageName, 0)
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.P) {
            pm.longVersionCode
        } else {
            @Suppress("DEPRECATION")
            pm.versionCode.toLong()
        }
    } catch (_: Exception) {
        0L
    }
}
