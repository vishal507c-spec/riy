package com.vishal.riy.update

import android.content.BroadcastReceiver
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.res.ColorStateList
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.core.content.ContextCompat
import androidx.fragment.app.DialogFragment
import com.google.android.material.button.MaterialButton
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.vishal.riy.R

/**
 * The USB / ADB development update screen.
 *
 * It appears instead of the system installer when Android will not let this app
 * install its own update ("Blocked by your IT admin"), and it deliberately
 * contains NO settings shortcut to the unknown-sources switch — that switch is
 * precisely the thing that cannot be used here, and offering it again is what
 * created the update loop.
 *
 * It also refuses to look clever: the state shown is the state the phone can
 * actually observe (a USB peripheral is attached, the APK is verified and staged)
 * plus the one fact that proves success (the installed version reached the
 * release version). "Update successful" is never guessed.
 */
class UsbUpdateFragment : DialogFragment() {

    private lateinit var info: ReleaseInfo
    private var versionName: String = ""
    private var versionCode: Long = 0L
    private var installedVersionCode: Long = 0L
    private var apkFileName: String = ""
    private var stagingName: String = ""
    private var exportedName: String? = null
    private var assessmentDisposition: String = ""
    private var adbCommand: String? = null
    private var messageRes: Int = R.string.update_ready_usb

    private var view: View? = null

    /**
     * Live USB link state while this screen is open. Registered dynamically (no
     * manifest change, no permission) and always unregistered in onDestroy.
     */
    private val usbReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) = refresh()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        isCancelable = true
        val args = requireArguments()
        info = args.releaseInfo()
            ?: throw IllegalStateException("UsbUpdateFragment requires release metadata")
        versionName = args.getString(ARG_VERSION_NAME).orEmpty()
        versionCode = args.getLong(ARG_VERSION_CODE)
        installedVersionCode = args.getLong(ARG_INSTALLED_VERSION_CODE)
        apkFileName = args.getString(ARG_APK_FILE_NAME).orEmpty()
        stagingName = args.getString(ARG_STAGING).orEmpty()
        exportedName = args.getString(ARG_EXPORTED_NAME)
        assessmentDisposition = args.getString(ARG_DISPOSITION).orEmpty()
        adbCommand = args.getString(ARG_ADB_COMMAND)
        messageRes = args.getInt(ARG_MESSAGE_RES, R.string.update_ready_usb)
    }

    override fun onCreateDialog(savedInstanceState: Bundle?): AlertDialog {
        val ctx = requireContext()
        val inflated = layoutInflater.inflate(R.layout.usb_update, null, false)
        view = inflated

        inflated.findViewById<TextView>(R.id.usbVersion).text =
            getString(R.string.usb_version_line, versionName)

        inflated.findViewById<MaterialButton>(R.id.usbCopyCommand).setOnClickListener {
            copyCommand()
        }
        inflated.findViewById<MaterialButton>(R.id.usbCheckConnection).setOnClickListener {
            refresh()
            Toast.makeText(ctx, R.string.usb_checked_connection, Toast.LENGTH_SHORT).show()
        }
        inflated.findViewById<MaterialButton>(R.id.usbRetry).setOnClickListener { restartUpdateFlow() }

        refresh()
        return MaterialAlertDialogBuilder(ctx)
            .setView(inflated)
            .create()
    }

    override fun onStart() {
        super.onStart()
        registerUsbListener()
    }

    override fun onResume() {
        super.onResume()
        // Coming back from the PC / a restart is the moment "update successful"
        // becomes knowable, so re-read it.
        refresh()
    }

    override fun onStop() {
        runCatching { requireContext().unregisterReceiver(usbReceiver) }
        super.onStop()
    }

    private fun registerUsbListener() {
        val filter = IntentFilter().apply {
            addAction(UsbUpdateController.ACTION_USB_ATTACHED)
            addAction(UsbUpdateController.ACTION_USB_DETACHED)
        }
        runCatching {
            ContextCompat.registerReceiver(
                requireContext(),
                usbReceiver,
                filter,
                ContextCompat.RECEIVER_NOT_EXPORTED,
            )
        }
    }

    /** Re-reads every observable fact and repaints the screen. Never throws. */
    private fun refresh() {
        val v = view ?: return
        val ctx = context ?: return

        val liveInstalled = currentInstalledVersionCode()
        val staging = stagingFromName()
        val link = UsbUpdateController.usbLinkState(ctx)
        val assessment = reconstructAssessment()
        val state = UsbUpdateController.screenState(
            link = link,
            assessment = assessment,
            installedVersionCode = liveInstalled,
            releaseVersionCode = versionCode,
            staging = staging,
        )

        v.findViewById<TextView>(R.id.usbStatusText).setText(state.messageRes())
        paintSeverity(v.findViewById(R.id.usbStatusRow), state.severity())
        v.findViewById<TextView>(R.id.usbStatusDetail).text = detailFor(state, assessment)

        val command = assessment.adbCommand
        val commandCard = v.findViewById<LinearLayout>(R.id.usbCommandCard)
        val commandView = v.findViewById<TextView>(R.id.usbCommand)
        val hintView = v.findViewById<TextView>(R.id.usbCommandHint)
        val copyButton = v.findViewById<MaterialButton>(R.id.usbCopyCommand)

        if (command.isNullOrEmpty()) {
            commandCard.visibility = View.GONE
            copyButton.visibility = View.GONE
        } else {
            commandCard.visibility = View.VISIBLE
            copyButton.visibility = View.VISIBLE
            copyButton.isEnabled = true
            commandView.text = command
            hintView.text = commandHint(ctx)
        }

        // The steps card is meaningless once the update is already installed.
        v.findViewById<LinearLayout>(R.id.usbStepsCard).visibility =
            if (state.status == UsbStatus.UPDATE_SUCCESSFUL) View.GONE else View.VISIBLE
    }

    private fun detailFor(state: UsbScreenState, assessment: ApkAssessment): String =
        when (state.status) {
            UsbStatus.UPDATE_SUCCESSFUL -> getString(R.string.usb_detail_success)
            UsbStatus.ALREADY_CURRENT -> getString(R.string.usb_detail_current)
            UsbStatus.APK_UNAVAILABLE -> getString(R.string.usb_detail_apk_missing)
            UsbStatus.UPDATE_REJECTED -> getString(assessment.messageRes)
            UsbStatus.READY_TO_UPDATE -> getString(R.string.usb_detail_connected)
            UsbStatus.WAITING_FOR_USB -> getString(R.string.usb_detail_not_connected)
        }

    /** Where the PC finds the APK, or how to get it. */
    private fun commandHint(ctx: Context): String = when {
        exportedName != null ->
            getString(R.string.usb_hint_exported, UsbUpdateController.exportedLocation(exportedName!!))

        else -> getString(R.string.usb_hint_pc_download, apkFileName)
    }

    private fun copyCommand() {
        val command = adbCommand ?: return
        val ctx = context ?: return
        runCatching {
            val clipboard = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
            clipboard?.setPrimaryClip(ClipData.newPlainText("adb", command))
        }
        Toast.makeText(ctx, R.string.usb_command_copied, Toast.LENGTH_SHORT).show()
    }

    /** Green for success, amber for "not yet / needs attention", red only on failure. */
    private fun paintSeverity(row: LinearLayout, severity: UsbSeverity) {
        val ctx = context ?: return
        val colorRes = when (severity) {
            UsbSeverity.SUCCESS -> R.color.riy_green
            UsbSeverity.WARNING -> R.color.riy_amber
            UsbSeverity.ERROR -> R.color.riy_red
            UsbSeverity.INFO -> R.color.riy_cyan
        }
        val color = ContextCompat.getColor(ctx, colorRes)
        row.backgroundTintList = ColorStateList.valueOf(withAlpha(color, 0.16f))
        val dot = row.findViewById<View>(R.id.usbStatusDot)
        dot.backgroundTintList = ColorStateList.valueOf(color)
        row.findViewById<TextView>(R.id.usbStatusText).setTextColor(color)
    }

    private fun withAlpha(color: Int, alpha: Float): Int {
        val a = (alpha * 255).toInt().coerceIn(0, 255)
        return (a shl 24) or (color and 0x00FFFFFF)
    }

    private fun stagingFromName(): UsbUpdateController.Staging = when (stagingName) {
        UsbUpdateController.STAGING_PC_DOWNLOAD -> UsbUpdateController.Staging.NeedsPcDownload
        UsbUpdateController.STAGING_EXPORTED ->
            UsbUpdateController.Staging.Exported(exportedName.orEmpty())
        else -> UsbUpdateController.Staging.Failed("unknown")
    }

    /** The verdict is carried across the fragment boundary as a name only. */
    private fun reconstructAssessment(): ApkAssessment = when (assessmentDisposition) {
        ApkDisposition.INSTALLABLE.name -> ApkAssessment(
            disposition = ApkDisposition.INSTALLABLE,
            adbCommand = adbCommand,
            messageRes = messageRes,
        )

        ApkDisposition.ALREADY_CURRENT.name -> ApkAssessment(
            disposition = ApkDisposition.ALREADY_CURRENT,
            messageRes = messageRes,
        )

        else -> ApkAssessment(
            disposition = ApkDisposition.REJECTED,
            rejection = ApkRejection.CORRUPT,
            messageRes = messageRes,
        )
    }

    /**
     * Retry restarts the whole route with fresh facts instead of closing the
     * flow. If the installed version has already reached this release, the
     * screen simply refreshes to success rather than re-downloading a stale
     * update.
     */
    private fun restartUpdateFlow() {
        val liveInstalled = currentInstalledVersionCode()
        if (info.versionCode > 0L && liveInstalled >= info.versionCode) {
            refresh()
            return
        }
        val manager = parentFragmentManager
        dismiss()
        runCatching {
            UpdateDialogFragment.newInstance(info, liveInstalled)
                .show(manager, UpdateDialogFragment::class.java.simpleName)
        }.onFailure { e ->
            Log.w(TAG, "retry could not restart update: ${e.javaClass.simpleName}")
        }
    }

    private fun Bundle.releaseInfo(): ReleaseInfo? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            getSerializable(ARG_INFO, ReleaseInfo::class.java)
        } else {
            @Suppress("DEPRECATION")
            getSerializable(ARG_INFO) as? ReleaseInfo
        }

    private fun currentInstalledVersionCode(): Long = try {
        val ctx = requireContext()
        val info = ctx.packageManager.getPackageInfo(ctx.packageName, 0)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) info.longVersionCode else {
            @Suppress("DEPRECATION")
            info.versionCode.toLong()
        }
    } catch (_: Exception) {
        installedVersionCode
    }

    override fun onDestroy() {
        view = null
        super.onDestroy()
    }

    companion object {
        const val TAG = "RiyUsbUpdate"

        private const val ARG_INFO = "release_info"
        private const val ARG_VERSION_NAME = "version_name"
        private const val ARG_VERSION_CODE = "version_code"
        private const val ARG_INSTALLED_VERSION_CODE = "installed_version_code"
        private const val ARG_APK_FILE_NAME = "apk_file_name"
        private const val ARG_STAGING = "staging"
        private const val ARG_EXPORTED_NAME = "exported_name"
        private const val ARG_DISPOSITION = "disposition"
        private const val ARG_ADB_COMMAND = "adb_command"
        private const val ARG_MESSAGE_RES = "message_res"

        fun newInstance(
            info: ReleaseInfo,
            versionName: String,
            versionCode: Long,
            installedVersionCode: Long,
            apkFileName: String,
            staging: String,
            exportedName: String?,
            assessmentDisposition: String,
            adbCommand: String?,
            messageRes: Int,
        ): UsbUpdateFragment = UsbUpdateFragment().apply {
            arguments = Bundle().apply {
                putSerializable(ARG_INFO, info)
                putString(ARG_VERSION_NAME, versionName)
                putLong(ARG_VERSION_CODE, versionCode)
                putLong(ARG_INSTALLED_VERSION_CODE, installedVersionCode)
                putString(ARG_APK_FILE_NAME, apkFileName)
                putString(ARG_STAGING, staging)
                putString(ARG_EXPORTED_NAME, exportedName)
                putString(ARG_DISPOSITION, assessmentDisposition)
                putString(ARG_ADB_COMMAND, adbCommand)
                putInt(ARG_MESSAGE_RES, messageRes)
            }
        }
    }
}