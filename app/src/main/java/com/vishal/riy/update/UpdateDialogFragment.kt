package com.vishal.riy.update

import android.content.Context
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.view.View
import android.widget.ProgressBar
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.DialogFragment
import androidx.lifecycle.lifecycleScope
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.vishal.riy.R
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Native "New Update Available" dialog, now with a REAL routing decision.
 *
 * The flow, in full:
 *
 *   Update Now
 *     → probe the real install environment
 *        → normal installer available      → download, verify, `PackageInstaller` session
 *        → unknown-sources screen works     → offer it ONCE (never again)
 *        → policy owns it / screen useless  → download, verify, stage, USB UPDATE screen
 *
 * `PackageInstaller.STATUS_FAILURE_BLOCKED` (the "Blocked by your IT admin"
 * result) routes into the same USB screen, so there is no code path that can send
 * the user back to a disabled switch. That is the fix for the reported loop.
 *
 * The download still runs in [lifecycleScope] on [Dispatchers.IO] and is
 * cancelled automatically when the Fragment is destroyed, exactly as before.
 */
class UpdateDialogFragment : DialogFragment() {

    private lateinit var info: ReleaseInfo
    private var installedVersionCode: Long = 0L

    private var dialogView: View? = null

    private val installPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
            // The grant screen closed. Whether it helped is decided by a fresh
            // probe on the next tap; the "prompted" flag is already set, so an
            // ineffective prompt can never come back around.
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        isCancelable = true
    }

    override fun onCreateDialog(savedInstanceState: Bundle?): AlertDialog {
        info = requireArguments().releaseInfo()
            ?: throw IllegalStateException("UpdateDialogFragment requires release metadata")
        installedVersionCode = requireArguments().getLong(ARG_INSTALLED_VERSION_CODE)

        val view = layoutInflater.inflate(R.layout.dialog_update, null, false)
        dialogView = view
        view.findViewById<TextView>(R.id.updateMessage).text =
            getString(R.string.update_message, currentVersionName(), info.versionName)

        val dialog = MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.update_title)
            .setView(view)
            .setPositiveButton(R.string.update_action_now, null)
            .setNegativeButton(R.string.update_action_later) { _, _ -> dismiss() }
            .create()
        // Wire "Update Now" manually so it does NOT auto-dismiss the dialog: the
        // default AlertDialog button auto-dismisses, which destroys this Fragment
        // and cancels its lifecycleScope (killing the app-controlled HTTPS download).
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE)?.setOnClickListener { onUpdateNow() }
        }
        return dialog
    }

    private fun currentVersionName(): String = try {
        requireContext().packageManager
            .getPackageInfo(requireContext().packageName, 0).versionName ?: "?"
    } catch (_: Exception) {
        "?"
    }

    /**
     * The whole routing decision happens BEFORE any download, so a device that
     * cannot install directly is told so immediately instead of after a 14 MB
     * download that ends at a dead settings screen.
     */
    private fun onUpdateNow() {
        val ctx = context ?: return
        val prefs = UpdatePreferences(ctx)
        val prompted = prefs.hasPromptedForInstallGrant()

        // If we are legitimately Device/Profile Owner, give the platform's own
        // managed flow a chance first (it lifts OUR restriction, nothing else).
        val blocked = prefs.isInstallBlockedObserved()
        val afterExemption = if (prefs.isDirectInstallRefused()) {
            InstallEnvironmentProbe.probe(ctx, prompted, blocked)
        } else {
            InstallEnvironmentProbe.allowOwnUpdateExemption(ctx, prompted, blocked)
        }

        when (val route = InstallRouteDecider.decide(afterExemption)) {
            is UpdateRoute.SystemInstaller -> {
                Log.i("RiyUpdate", "update route=system reason=${route.reason}")
                startDownload()
            }

            UpdateRoute.UserGrantSettings -> {
                Log.i("RiyUpdate", "update route=user_grant (offered once)")
                showGrantDialogOnce(prefs)
            }

            is UpdateRoute.UsbAdb -> {
                // Remembered, so no future tap re-explores the system installer.
                prefs.markDirectInstallRefused()
                Log.i("RiyUpdate", "update route=usb blocker=${route.blocker}")
                showRestrictedIntro(ctx, route) { startDownload(forUsb = true) }
            }
        }
    }

    private fun showGrantDialogOnce(prefs: UpdatePreferences) {
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.update_install_permission_title)
            .setMessage(R.string.update_install_permission_message)
            .setPositiveButton(android.R.string.ok) { _, _ -> openGrantSettings(prefs) }
            .setNegativeButton(R.string.update_action_later, null)
            .show()
    }

    /**
     * Explains the restriction BEFORE downloading anything, then lets the user
     * choose USB explicitly. Retry re-probes the live environment; USB starts the
     * staged download. Neither button returns to the unusable settings switch.
     */
    private fun showRestrictedIntro(
        ctx: Context,
        route: UpdateRoute.UsbAdb,
        onUsbUpdate: () -> Unit,
    ) {
        val message = getString(route.blockerMessageRes()) +
            "\n\n" + getString(R.string.usb_detail_not_connected)
        val intro = MaterialAlertDialogBuilder(ctx)
            .setTitle(R.string.update_restricted_title)
            .setMessage(message)
            .setPositiveButton(R.string.update_restricted_usb, null)
            .setNeutralButton(R.string.usb_action_retry, null)
            .setNegativeButton(R.string.update_action_later, null)
            .create()
        intro.setOnShowListener {
            intro.getButton(AlertDialog.BUTTON_POSITIVE)?.setOnClickListener {
                intro.dismiss()
                onUsbUpdate()
            }
            intro.getButton(AlertDialog.BUTTON_NEUTRAL)?.setOnClickListener {
                intro.dismiss()
                onUpdateNow()
            }
        }
        intro.show()
    }

    /**
     * Opens the unknown-sources screen, recording the attempt FIRST. From now on
     * the app will never send the user here a second time; if the switch turns out
     * to be ineffective, the next tap goes to the USB/ADB path.
     */
    private fun openGrantSettings(prefs: UpdatePreferences) {
        if (!isAdded) return
        prefs.markPromptedForInstallGrant()
        installPermissionLauncher.launch(UpdateInstaller.installPermissionIntent(requireActivity()))
    }

    // ---------------------------------------------------------------- download

    private fun startDownload(forUsb: Boolean = false) {
        val ctx = context ?: return // only proceed while attached
        showProgress(getString(R.string.update_downloading, 0), 0)
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val installed = InstallEnvironmentProbe.installedApkFacts(ctx)
                val temp = UpdateInstaller.downloadToTemp(ctx, info) { downloaded, total ->
                    val pct = if (total > 0L) ((downloaded * 100) / total).toInt().coerceIn(0, 100) else 0
                    withContext(Dispatchers.Main) {
                        if (isAdded) showProgress(getString(R.string.update_downloading, pct), pct)
                    }
                }

                // Full pre-flight: package, version AND signing certificate, before
                // Android or a PC is ever asked to install anything.
                val candidate = InstallEnvironmentProbe.candidateApkFacts(ctx, temp)
                val assessment = ApkCompatibility.assess(installed, candidate, UpdateInstaller.apkFileName(info.versionName))

                when (assessment.disposition) {
                    ApkDisposition.REJECTED -> {
                        temp.delete()
                        withContext(Dispatchers.Main) {
                            if (isAdded) showFailure(getString(assessment.messageRes))
                        }
                        return@launch
                    }

                    ApkDisposition.ALREADY_CURRENT -> {
                        temp.delete()
                        withContext(Dispatchers.Main) {
                            if (isAdded) {
                                showFailure(getString(R.string.update_reject_same_version))
                            }
                        }
                        return@launch
                    }

                    ApkDisposition.INSTALLABLE -> Unit
                }

                val final = UpdateInstaller.commitApk(temp, UpdateInstaller.apkFile(ctx, info))
                withContext(Dispatchers.Main) {
                    if (!isAdded) return@withContext
                    showProgress(getString(R.string.update_installing), 100)
                    if (forUsb) openUsbUpdate(final, assessment) else installWithSystemInstaller(final)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    if (isAdded) showFailure(getString(R.string.update_failed))
                }
            }
        }
    }

    /**
     * PATH A — the normal Android installer, via a real `PackageInstaller`
     * session so success / cancel / policy-block / failure are all reported.
     */
    private fun installWithSystemInstaller(apkFile: File) {
        val ctx = context ?: return
        dismissProgress()
        dismiss()
        val started = PackageInstallerLauncher.install(ctx, apkFile) { outcome ->
            activity?.runOnUiThread { handleInstallOutcome(outcome) }
        }
        if (!started) {
            // Session creation itself failed — fall through to USB mode rather
            // than leaving the user with nothing to do.
            openUsbUpdate(
                apkFile,
                ApkCompatibility.assess(
                    InstallEnvironmentProbe.installedApkFacts(ctx),
                    InstallEnvironmentProbe.candidateApkFacts(ctx, apkFile),
                    UpdateInstaller.apkFileName(info.versionName),
                ),
            )
        }
    }

    /**
     * Handles the platform's answer.
     *
     * `BlockedByPolicy` is the exact "Blocked by your IT admin" case: instead of
     * re-opening the dead settings switch, remember the refusal and offer USB.
     */
    private fun handleInstallOutcome(outcome: PackageInstallerLauncher.Outcome) {
        val ctx = context ?: activity?.applicationContext ?: return
        val prefs = UpdatePreferences(ctx)
        when (outcome) {
            is PackageInstallerLauncher.Outcome.Success -> {
                // Nothing to do: the new version is live. Protection self-heals on
                // its own at the next foreground reconciliation.
            }

            PackageInstallerLauncher.Outcome.Cancelled -> Unit // user chose not to

            PackageInstallerLauncher.Outcome.BlockedByPolicy -> {
                // The platform told us an administrator owns this. Persist that
                // answer so no future tap ever re-opens the dead settings switch.
                prefs.markDirectInstallRefused()
                prefs.markInstallBlockedObserved()
                val apk = UpdateInstaller.apkFile(ctx, info)
                if (apk.exists()) {
                    openUsbUpdate(
                        apk,
                        ApkCompatibility.assess(
                            InstallEnvironmentProbe.installedApkFacts(ctx),
                            InstallEnvironmentProbe.candidateApkFacts(ctx, apk),
                            UpdateInstaller.apkFileName(info.versionName),
                        ),
                    )
                }
            }

            is PackageInstallerLauncher.Outcome.Failed -> {
                // Only an attached Activity can host a window. An application
                // context would crash here after the installer returns.
                val host = activity ?: return
                MaterialAlertDialogBuilder(host)
                    .setTitle(R.string.update_title)
                    .setMessage(R.string.update_failed)
                    .setPositiveButton(android.R.string.ok, null)
                    .show()
            }
        }
    }

    /**
     * PATH B — USB/ADB. Stage the verified APK so a development PC can reach it,
     * then show the polished USB screen. Never opens a settings switch.
     */
    private fun openUsbUpdate(apkFile: File, assessment: ApkAssessment) {
        val ctx = context ?: return
        dismissProgress()
        dismiss()
        val staging = UsbUpdateController.stageForPc(ctx, apkFile, info)
        UsbUpdateFragment.newInstance(
            info = info,
            versionName = info.versionName,
            versionCode = info.versionCode,
            installedVersionCode = installedVersionCode,
            apkFileName = UpdateInstaller.apkFileName(info.versionName),
            staging = when (staging) {
                is UsbUpdateController.Staging.Exported -> UsbUpdateController.STAGING_EXPORTED
                UsbUpdateController.Staging.NeedsPcDownload -> UsbUpdateController.STAGING_PC_DOWNLOAD
                is UsbUpdateController.Staging.Failed -> UsbUpdateController.STAGING_FAILED
            },
            exportedName = (staging as? UsbUpdateController.Staging.Exported)?.displayName,
            assessmentDisposition = assessment.disposition.name,
            adbCommand = assessment.adbCommand,
            messageRes = assessment.messageRes,
        ).show(parentFragmentManager, UsbUpdateFragment.TAG)
    }

    private fun showFailure(message: String) {
        val ctx = context ?: return // detached -> cannot show a dialog
        dismissProgress()
        dismiss()
        MaterialAlertDialogBuilder(ctx)
            .setTitle(R.string.update_title)
            .setMessage(message)
            .setPositiveButton(R.string.update_retry) { _, _ -> startDownload() }
            .setNegativeButton(R.string.update_action_later, null)
            .show()
    }

    // ------------------------------------------------------- progress plumbing

    private fun showProgress(text: String, percent: Int) {
        val view = dialogView ?: return
        view.findViewById<TextView>(R.id.updateStatus).apply {
            visibility = View.VISIBLE
            this.text = text
        }
        view.findViewById<ProgressBar>(R.id.updateProgress).apply {
            visibility = View.VISIBLE
            isIndeterminate = false
            progress = percent
        }
        (dialog as? AlertDialog)?.getButton(AlertDialog.BUTTON_POSITIVE)?.isEnabled = false
    }

    private fun dismissProgress() {
        val view = dialogView ?: return
        view.findViewById<TextView>(R.id.updateStatus).visibility = View.GONE
        view.findViewById<ProgressBar>(R.id.updateProgress).visibility = View.GONE
        (dialog as? AlertDialog)?.getButton(AlertDialog.BUTTON_POSITIVE)?.isEnabled = true
    }

    override fun onDestroy() {
        // Downloads run in lifecycleScope and are cancelled here automatically;
        // the coroutine deletes its partial temp file. No manual unregister needed.
        dialogView = null
        super.onDestroy()
    }

    private fun Bundle.releaseInfo(): ReleaseInfo? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            getSerializable(ARG_INFO, ReleaseInfo::class.java)
        } else {
            @Suppress("DEPRECATION")
            getSerializable(ARG_INFO) as? ReleaseInfo
        }

    companion object {
        private const val ARG_INFO = "release_info"
        private const val ARG_INSTALLED_VERSION_CODE = "installed_version_code"

        fun newInstance(info: ReleaseInfo, installedVersionCode: Long): UpdateDialogFragment =
            UpdateDialogFragment().apply {
                arguments = Bundle().apply {
                    putSerializable(ARG_INFO, info)
                    putLong(ARG_INSTALLED_VERSION_CODE, installedVersionCode)
                }
            }
    }
}