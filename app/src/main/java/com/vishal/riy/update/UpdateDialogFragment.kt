package com.vishal.riy.update

import android.os.Bundle
import android.view.LayoutInflater
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
 * Native "New Update Available" dialog.
 *
 *  - [Later]  -> dismisses; the app stays usable and the check happens again at a
 *                future lifecycle event (throttled).
 *  - [Update Now] -> downloads the private APK via an app-controlled authenticated
 *                HTTPS request to the GitHub Releases Assets API (no DownloadManager),
 *                shows real progress, verifies the APK, then hands it to the system
 *                installer. Failures show a clear message and keep the app usable.
 *
 * The download runs in [lifecycleScope] on [Dispatchers.IO]; it is cancelled
 * automatically (and its temp file cleaned up) if the Fragment is destroyed, so a
 * detached Fragment is never touched and no UI update occurs on a stale Fragment.
 */
class UpdateDialogFragment : DialogFragment() {

    private lateinit var info: ReleaseInfo
    private var installedVersionCode: Long = 0L

    private var dialogView: View? = null
    private var installAfterPermission = false

    private val installPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
            // After granting "install unknown apps" the user taps "Update Now" again
            // to start the download; there is no verified APK here yet, so there is
            // nothing to install. Just clear the retry flag (lifecycle-safe).
            if (installAfterPermission) installAfterPermission = false
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        isCancelable = true
    }

    override fun onCreateDialog(savedInstanceState: Bundle?): AlertDialog {
        @Suppress("UNCHECKED_CAST")
        info = requireArguments().getSerializable(ARG_INFO) as ReleaseInfo
        installedVersionCode = requireArguments().getLong(ARG_INSTALLED_VERSION_CODE)

        val view = LayoutInflater.from(requireContext()).inflate(R.layout.dialog_update, null, false)
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
        // The dialog is dismissed only through the success/failure flow.
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

    private fun onUpdateNow() {
        if (!UpdateInstaller.canRequestInstalls(requireContext())) {
            installAfterPermission = true
            MaterialAlertDialogBuilder(requireContext())
                .setTitle(R.string.update_install_permission_title)
                .setMessage(R.string.update_install_permission_message)
                .setPositiveButton(android.R.string.ok) { _, _ -> onPermissionOk() }
                .setNegativeButton(R.string.update_action_later, null)
                .show()
            return
        }
        startDownload()
    }

    /**
     * Opens the "install unknown apps" settings screen. Lifecycle-safe: if this
     * Fragment is no longer attached there is no valid Activity to launch the
     * settings from, so the tap is ignored instead of calling requireActivity()
     * on a stale Fragment.
     */
    private fun onPermissionOk() {
        if (!isAdded) return
        installPermissionLauncher.launch(UpdateInstaller.installPermissionIntent(requireActivity()))
    }

    // ---------------------------------------------------------------- download

    private fun startDownload() {
        val ctx = context ?: return // only proceed while attached
        showProgress(getString(R.string.update_downloading, 0), 0)
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val temp = UpdateInstaller.downloadToTemp(ctx, info) { downloaded, total ->
                    val pct = if (total > 0L) ((downloaded * 100) / total).toInt().coerceIn(0, 100) else 0
                    withContext(Dispatchers.Main) {
                        if (isAdded) showProgress(getString(R.string.update_downloading, pct), pct)
                    }
                }

                if (!UpdateInstaller.verifyApk(ctx, temp, installedVersionCode)) {
                    temp.delete()
                    withContext(Dispatchers.Main) {
                        if (isAdded) showFailure(getString(R.string.update_verification_failed))
                    }
                    return@launch
                }

                val final = UpdateInstaller.commitApk(temp, UpdateInstaller.apkFile(ctx, info))
                withContext(Dispatchers.Main) {
                    if (!isAdded) return@withContext
                    showProgress(getString(R.string.update_installing), 100)
                    startInstallation(final)
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

    private fun startInstallation(apkFile: File) {
        val ctx = context ?: return // detached -> never touch a stale context
        if (!UpdateInstaller.verifyApk(ctx, apkFile, installedVersionCode)) {
            showFailure(getString(R.string.update_verification_failed))
            return
        }
        dismissProgress()
        dismiss()
        // Self-update exemption FIRST: our own Device Owner hardening blocks
        // unknown-source installs while protection is active (system shows
        // "Blocked by your IT admin"), and a per-app grant cannot override a
        // Device Owner restriction — so lift it for this verified update.
        // Reconciliation re-raises it automatically afterwards.
        UpdateInstaller.allowSelfUpdateInstall(ctx)
        // Android verifies the APK signature against the installed app here;
        // a mismatch is rejected by the OS.
        ctx.startActivity(UpdateInstaller.buildInstallIntent(ctx, apkFile))
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
