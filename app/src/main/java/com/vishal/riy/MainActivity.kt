package com.vishal.riy

import android.os.Bundle
import android.util.Log
import androidx.activity.compose.setContent
import androidx.appcompat.app.AppCompatActivity
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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/** TEMPORARY diagnostic tag; never contains credentials. */
private const val LOG_TAG = "RiyUpdate"

/**
 * App entry point. The whole UI is a single Compose screen ([RiyApp]) that
 * shows either the lock countdown or the protection status. The only other
 * behaviour is the asynchronous GitHub auto-update check fired on startup:
 * it runs on Dispatchers.IO, never blocks rendering, and silently no-ops on
 * any failure.
 */
class MainActivity : AppCompatActivity() {

    private lateinit var updatePreferences: UpdatePreferences

    // Pending update + lifecycle-safe dialog showing (see showUpdateDialogIfReady).
    private var pendingUpdate: ReleaseInfo? = null
    private var pendingInstalledCode: Long = 0L
    private var updateDialogShown = false
    private var resumeObserver: LifecycleEventObserver? = null

    // Drive backup: process-wide debounced scheduler + network-triggered flush.
    private val driveScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val driveManager by lazy { com.vishal.riy.drive.DriveBackupManager(applicationContext) }
    private lateinit var googleSignInClient: com.google.android.gms.auth.api.signin.GoogleSignInClient
    private lateinit var driveSignInLauncher: androidx.activity.result.ActivityResultLauncher<android.content.Intent>
    private var networkObserver: com.vishal.riy.drive.BackupNetworkObserver? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { RiyApp() }
        updatePreferences = UpdatePreferences(this)
        scheduleUpdateCheck()
        restoreProtectionIfInterrupted()
        protectUninstallIfOwner()
        installDriveBackup()
    }

    override fun onStart() {
        super.onStart()
        restoreProtectionIfInterrupted()
        // Re-register network callbacks (released in onStop) and replay any
        // pending Drive sync on EVERY foreground (never blocks UI).
        ensureNetworkObserver()
        driveScope.launch { runCatching { driveManager.flushPendingSync() } }
    }

    override fun onStop() {
        networkObserver?.unregister()
        networkObserver = null
        super.onStop()
    }

    /**
     * Drive backup bootstrap (cloned from the reference ordering):
     * scheduler install → network observer → reconcile → authorize (OAuth
     * consent only when needed) → restore-if-empty → catch-up sync.
     * Every step is best-effort; protection never depends on Drive.
     */
    private fun installDriveBackup() {
        try {
            com.vishal.riy.drive.DriveSync.install(
                com.vishal.riy.drive.DriveBackupScheduler(
                    scope = driveScope,
                    backupFn = { driveManager.backupAfterTransaction() },
                ),
            )
        } catch (_: Exception) {
            // Drive must never break startup.
        }
        try {
            googleSignInClient = com.google.android.gms.auth.api.signin.GoogleSignIn
                .getClient(this, com.vishal.riy.drive.GoogleDriveAuth.signInOptions())
            driveSignInLauncher = registerForActivityResult(
                androidx.activity.result.contract.ActivityResultContracts.StartActivityForResult(),
            ) { r -> handleDriveSignInResult(r.data) }
        } catch (_: Exception) {
            // Sign-in unavailable; Drive stays disconnected.
        }
        ensureNetworkObserver()
        driveScope.launch { runDriveBootstrap() }
    }

    /** (Re-)registers connectivity-triggered pending-sync flush. Never throws. */
    private fun ensureNetworkObserver() {
        if (networkObserver != null) return
        try {
            networkObserver = com.vishal.riy.drive.BackupNetworkObserver(
                applicationContext, driveScope, driveManager,
            ).also { it.register() }
        } catch (_: Exception) {
            // Network callbacks unavailable; pending sync still flushes on foreground.
            networkObserver = null
        }
    }

    private suspend fun runDriveBootstrap() {
        runCatching { driveManager.reconcileOnStartup() }
        if (runCatching { driveManager.driveNeedsAuthorization() }.getOrDefault(false)) {
            // ActivityResultLauncher must launch on the main thread.
            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
                runCatching { driveSignInLauncher.launch(googleSignInClient.signInIntent) }
            }
            return // the sign-in result continues the bootstrap.
        }
        runCatching { driveManager.autoRestoreIfNeeded() }
        runCatching { driveManager.reconcileAndCatchUp() }
    }

    private fun handleDriveSignInResult(data: android.content.Intent?) {
        val account = com.vishal.riy.drive.GoogleDriveAuth.accountFromIntent(data)
            ?: return // user cancelled; retry on next launch.
        val email = account.email ?: return
        driveScope.launch {
            // Ordered flow: auth → folder → restore → sync (same as reference).
            runCatching {
                driveManager.connectDrive(email, account.displayName, account.photoUrl?.toString())
            }
            runCatching { driveManager.finalizeProvisioning() }
            runCatching { driveManager.autoRestoreIfNeeded() }
            runCatching { driveManager.reconcileAndCatchUp() }
            runCatching { driveManager.finalizeProvisioning() }
        }
    }

    /**
     * Self-heal: re-applies Device Owner uninstall protection at launch. This
     * covers the case where the admin receiver was enabled before the owner
     * state was committed during provisioning (a timing race), in which case
     * the receiver's own call was a safe no-op.
     */
    private fun protectUninstallIfOwner() {
        try {
            com.vishal.riy.admin.UninstallProtection.apply(this)
        } catch (e: Exception) {
            Log.e("BlockerApp", "failed to apply uninstall protection", e)
        }
    }

    /**
     * Self-heal: if the user wants protection but the service is not running
     * (process death, force-stop, missed boot broadcast), restart it. This
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
