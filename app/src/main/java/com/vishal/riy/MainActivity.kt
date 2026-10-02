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
import com.vishal.riy.drive.DriveSessionPolicy
import com.vishal.riy.drive.GoogleDriveAuth
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
        // Cockpit soundboard (best-effort; never blocks startup).
        com.vishal.riy.ui.SciFiSound.init(this)
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
        // Foreground return: reconcile installed packages against the
        // protected-mode policy (single entry point — catches installs that
        // landed while RIY was dead). Never blocks the UI.
        try {
            com.vishal.riy.protection.integrity.ProtectionReconciler.reconcileAll(
                this, "foreground",
            )
        } catch (e: Exception) {
            Log.e("BlockerApp", "foreground reconciliation failed", e)
        }
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
                .getClient(this, GoogleDriveAuth.signInOptions())
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

    /**
     * Silent-first Drive bootstrap: the interactive account picker is the LAST
     * resort. Order:
     *  1. re-attach remembered wiring (no UI);
     *  2. ask [restoreDriveSessionSilently] for evidence, and let the
     *     unit-tested [DriveSessionPolicy] decide enter-vs-picker;
     *  3. when it says enter, restore and reconcile (no picker);
     *  4. ONLY when it says a login is genuinely required (no session,
     *     explicit logout, or a revoked/removed grant) launch the picker.
     *
     * Runs exactly once per process. Every step is best-effort; protection
     * never depends on Drive.
     */
    private suspend fun runDriveBootstrap() {
        runCatching { driveManager.reconcileOnStartup() }
        // One decision, made by the unit-tested state machine, so "do we show
        // the picker?" can never disagree with the tested session matrix.
        val action = runCatching { restoreDriveSessionSilently() }
            .getOrDefault(DriveSessionPolicy.SessionAction.SHOW_PICKER)
        if (action == DriveSessionPolicy.SessionAction.ENTER_SILENT) {
            runCatching { driveManager.autoRestoreIfNeeded() }
            runCatching { driveManager.reconcileAndCatchUp() }
            return
        }
        // A picker is genuinely required: no stored session, an explicit
        // logout, or a grant that no longer exists (revoked / account removed).
        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
            runCatching { driveSignInLauncher.launch(googleSignInClient.signInIntent) }
        }
    }

    /**
     * Restores the Drive session WITHOUT any UI and reports what the caller
     * must do next. Evidence gathered here, judged by [DriveSessionPolicy]:
     *
     *  1. remembered account + a genuinely obtainable OAuth token → ENTER_SILENT
     *     (covers reopen, force-stop, app update and token expiry);
     *  2. nothing remembered, but the platform can silently authenticate the
     *     same grant and a token follows → adopt it → ENTER_SILENT;
     *  3. anything else → SHOW_PICKER.
     *
     * The GoogleSignIn cache is passed as a hint only; the token decides.
     */
    private suspend fun restoreDriveSessionSilently(): DriveSessionPolicy.SessionAction {
        val loggedOut = runCatching { driveManager.isLoggedOut() }.getOrDefault(false)
        val stored = runCatching { driveManager.storedAccount() }.getOrDefault(null)

        // 1. Returning user: a real token for the remembered account.
        if (stored != null) {
            val valid = runCatching { driveManager.validateStoredSession() }.getOrDefault(false)
            return DriveSessionPolicy.decide(
                DriveSessionPolicy.SessionInputs(
                    storedEmail = stored,
                    platformEmail = GoogleDriveAuth.lastSignedIn(this)?.email,
                    silentSignInOk = false,
                    tokenOk = valid,
                    userLoggedOut = loggedOut,
                ),
            )
        }

        // 2. Nothing remembered (fresh install / storage cleared / reinstall):
        //    adopt a grant the platform can still authenticate silently.
        //    NEVER after an explicit logout — adopting here would immediately
        //    clear the logged-out marker and silently undo the logout.
        var silentOk = false
        if (!loggedOut) {
            val adopted = runCatching {
                val account = com.google.android.gms.tasks.Tasks.await(
                    googleSignInClient.silentSignIn(),
                )
                val email = account.email
                if (email.isNullOrBlank()) return@runCatching null
                driveManager.connectDrive(email, account.displayName, account.photoUrl?.toString())
                driveManager.finalizeProvisioning()
                email
            }.getOrNull()
            if (adopted != null) {
                silentOk = GoogleDriveAuth.usableToken(this, adopted) != null
            }
        }
        return DriveSessionPolicy.decide(
            DriveSessionPolicy.SessionInputs(
                storedEmail = null,
                platformEmail = null,
                silentSignInOk = silentOk,
                tokenOk = silentOk,
                userLoggedOut = loggedOut,
            ),
        )
    }

    private fun handleDriveSignInResult(data: android.content.Intent?) {
        if (data == null) {
            android.util.Log.w("RiyDrive", "sign-in returned with no result intent (cancelled)")
            return // user cancelled; retry on next launch.
        }
        val account = GoogleDriveAuth.accountFromIntent(data)
            ?: return // failure already logged; retry on next launch.
        val email = account.email
        if (email.isNullOrBlank()) {
            android.util.Log.w("RiyDrive", "sign-in succeeded but account has no email")
            return
        }
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
