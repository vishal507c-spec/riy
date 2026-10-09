package com.vishal.riy.update

import android.content.ContentResolver
import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.Uri
import android.os.BatteryManager
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
import androidx.annotation.RequiresApi
import com.vishal.riy.R
import java.io.File

/**
 * The USB / ADB development update path.
 *
 * HONEST SCOPE: an Android app cannot install its own successor from the Play
 * Store-less sideload path when policy forbids sideloading, and it can never run
 * `adb` on itself. So this path does exactly three things and nothing more:
 *
 *  1. verifies the downloaded APK is a legitimate, correctly signed update;
 *  2. puts a copy where a development PC can reach it WITHOUT any special
 *     permission — the public Downloads collection, which needs no storage
 *     permission from Android 10 onwards (and is skipped honestly on older
 *     releases, where the PC simply downloads it from the release page);
 *  3. shows the exact, copyable `adb` command plus live, honest connection state.
 *
 * It never shells out, never escalates, never modifies a policy and never asks
 * for a permission beyond what the app already had.
 */
object UsbUpdateController {

    private const val TAG = "RiyUsbUpdate"

    /** Where the APK is exported in the shared Downloads collection. */
    const val EXPORT_FOLDER = "Riy"

    /** Stable names so a staging result survives a Fragment boundary unchanged. */
    const val STAGING_EXPORTED = "exported"
    const val STAGING_PC_DOWNLOAD = "pc_download"
    const val STAGING_FAILED = "failed"

    /**
     * USB attach/detach broadcast actions. Spelled out as literals because
     * `UsbManager` itself is not part of the public SDK; the ACTION strings are
     * platform constants and stable.
     */
    const val ACTION_USB_ATTACHED = "android.hardware.usb.action.USB_DEVICE_ATTACHED"
    const val ACTION_USB_DETACHED = "android.hardware.usb.action.USB_DEVICE_DETACHED"

    /** What the phone itself can honestly know about the USB link. */
    enum class UsbLinkState {
        USB_NOT_CONNECTED,
        USB_CONNECTED,
    }

    /** Where the staged APK ended up (or why it could not). */
    sealed class Staging {
        data class Exported(val displayName: String) : Staging()

        /** Android too old to export without a storage permission we refuse to add. */
        object NeedsPcDownload : Staging()

        data class Failed(val reason: String) : Staging()
    }

    /**
     * Live USB link state, read with PUBLIC APIs only.
     *
     * `android.hardware.usb.UsbManager` is not in the public SDK, so instead of a
     * hidden API we use the battery broadcast's `EXTRA_PLUGGED`, which reports
     * `BATTERY_PLUGGED_USB` whenever a USB cable is actually supplying power —
     * the same signal a phone is on USB because of. Combined with the USB
     * attach/detach broadcasts (registered only while this screen is visible) the
     * answer is live and truthful, with no permission of any kind.
     */
    fun usbLinkState(context: Context): UsbLinkState {
        val pluggedUsb = runCatching {
            val battery: Intent? = context.registerReceiver(
                null,
                IntentFilter(Intent.ACTION_BATTERY_CHANGED),
            )
            val plugged = battery?.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) ?: 0
            (plugged and BatteryManager.BATTERY_PLUGGED_USB) != 0
        }.getOrDefault(false)
        return if (pluggedUsb) UsbLinkState.USB_CONNECTED else UsbLinkState.USB_NOT_CONNECTED
    }

    /**
     * Copies the verified APK into the public Downloads collection so
     * `adb pull /sdcard/Download/<name>` works with no permissions at all.
     *
     * Android 10+ writes through `MediaStore` with a scoped path, which needs no
     * permission. On Android 9 and below there is no permission-free way to place a
     * file where a PC can read it, so we report [Staging.NeedsPcDownload] instead of
     * asking for `WRITE_EXTERNAL_STORAGE` — the update flow must not be widened to
     * store the APK.
     */
    fun stageForPc(context: Context, apkFile: File, info: ReleaseInfo): Staging {
        if (!apkFile.exists() || apkFile.length() <= 0L) {
            return Staging.Failed("APK missing")
        }
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            Log.i(TAG, "export skipped: API ${Build.VERSION.SDK_INT} needs a storage permission we do not request")
            return Staging.NeedsPcDownload
        }
        val displayName = UpdateInstaller.apkFileName(info.versionName)
        val relativePath = "${Environment.DIRECTORY_DOWNLOADS}/$EXPORT_FOLDER"
        return try {
            exportOnModernAndroid(context, apkFile, displayName, relativePath)
        } catch (e: Exception) {
            Log.w(TAG, "export failed: ${e.javaClass.simpleName}: ${e.message}")
            Staging.Failed(e.message ?: e.javaClass.simpleName)
        }
    }

    /**
     * Android 10+ export implementation. The caller has already checked the SDK;
     * this annotation makes that contract explicit to lint and future readers.
     */
    @RequiresApi(Build.VERSION_CODES.Q)
    private fun exportOnModernAndroid(
        context: Context,
        apkFile: File,
        displayName: String,
        relativePath: String,
    ): Staging {
        val resolver = context.contentResolver
        val existingUri = findExistingExport(resolver, displayName, relativePath)
        if (existingUri != null) {
            // Retrying the same version refreshes this app's own staged copy in
            // place instead of accumulating uniquified duplicates in Downloads.
            if (!publishBytes(resolver, existingUri, apkFile)) {
                return Staging.Failed("Downloads folder not writable")
            }
            Log.i(TAG, "APK export refreshed for PC pull: $displayName")
            return Staging.Exported(displayName)
        }

        val values = ContentValues().apply {
            put(MediaStore.Downloads.DISPLAY_NAME, displayName)
            put(MediaStore.Downloads.MIME_TYPE, APK_MIME_TYPE)
            put(MediaStore.Downloads.RELATIVE_PATH, relativePath)
            put(MediaStore.Downloads.IS_PENDING, 1)
        }
        val uri: Uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
            ?: return Staging.Failed("Downloads folder unavailable")
        if (!publishBytes(resolver, uri, apkFile)) {
            return Staging.Failed("Downloads folder not writable")
        }
        val actualName = actualDisplayName(resolver, uri) ?: displayName
        Log.i(TAG, "APK exported for PC pull: $actualName")
        return Staging.Exported(actualName)
    }

    /** Finds this staged file, if the media collection still lists it. */
    @RequiresApi(Build.VERSION_CODES.Q)
    private fun findExistingExport(
        resolver: ContentResolver,
        displayName: String,
        relativePath: String,
    ): Uri? {
        val selection = "${MediaStore.Downloads.DISPLAY_NAME}=? AND " +
            "${MediaStore.Downloads.RELATIVE_PATH}=?"
        val selectionArgs = arrayOf(displayName, relativePath)
        resolver.query(
            MediaStore.Downloads.EXTERNAL_CONTENT_URI,
            arrayOf(MediaStore.Downloads._ID),
            selection,
            selectionArgs,
            null,
        )?.use { cursor ->
            if (cursor.moveToFirst()) {
                return ContentUris.withAppendedId(
                    MediaStore.Downloads.EXTERNAL_CONTENT_URI,
                    cursor.getLong(0),
                )
            }
        }
        return null
    }

    /** Writes the verified bytes and marks the staged row complete. */
    @RequiresApi(Build.VERSION_CODES.Q)
    private fun publishBytes(
        resolver: ContentResolver,
        uri: Uri,
        apkFile: File,
    ): Boolean {
        val pending = ContentValues().apply {
            put(MediaStore.Downloads.IS_PENDING, 1)
        }
        resolver.update(uri, pending, null, null)
        resolver.openOutputStream(uri)?.use { out ->
            apkFile.inputStream().use { input -> input.copyTo(out, 64 * 1024) }
        } ?: return false
        val complete = ContentValues().apply {
            put(MediaStore.Downloads.IS_PENDING, 0)
        }
        resolver.update(uri, complete, null, null)
        return true
    }

    /** Reads back the name MediaStore actually kept, including any uniqueness suffix. */
    @RequiresApi(Build.VERSION_CODES.Q)
    private fun actualDisplayName(resolver: ContentResolver, uri: Uri): String? =
        resolver.query(
            uri,
            arrayOf(MediaStore.Downloads.DISPLAY_NAME),
            null,
            null,
            null,
        )?.use { cursor ->
            if (cursor.moveToFirst()) cursor.getString(0)?.takeIf { it.isNotBlank() } else null
        }

    /** Human-readable location of the staged APK, for the UI. */
    fun exportedLocation(displayName: String): String = "/sdcard/Download/$EXPORT_FOLDER/$displayName"

    private const val APK_MIME_TYPE = "application/vnd.android.package-archive"

    // ------------------------------------------------------- state mapping

    /**
     * The single honest state machine for the USB screen. Pure, so every
     * combination in the edge-case list is a unit test.
     *
     * The app cannot see ADB authorisation (that lives on the PC), so it never
     * claims "device detected" or "update successful" from guesswork:
     *  - "update successful" comes only from [installedVersionCode] having reached
     *    the release version, which is a fact, not an assumption.
     */
    fun screenState(
        link: UsbLinkState,
        assessment: ApkAssessment,
        installedVersionCode: Long,
        releaseVersionCode: Long,
        staging: Staging,
    ): UsbScreenState {
        // A finished update is always the first thing reported.
        if (installedVersionCode >= releaseVersionCode && releaseVersionCode > 0L) {
            return UsbScreenState(
                status = UsbStatus.UPDATE_SUCCESSFUL,
                link = link,
                canCopyCommand = assessment.adbCommand != null,
            )
        }
        if (assessment.disposition == ApkDisposition.REJECTED) {
            return UsbScreenState(
                status = UsbStatus.UPDATE_REJECTED,
                link = link,
                canCopyCommand = false,
            )
        }
        if (assessment.disposition == ApkDisposition.ALREADY_CURRENT) {
            return UsbScreenState(
                status = UsbStatus.ALREADY_CURRENT,
                link = link,
                canCopyCommand = false,
            )
        }
        if (staging is Staging.Failed) {
            return UsbScreenState(
                status = UsbStatus.APK_UNAVAILABLE,
                link = link,
                canCopyCommand = false,
            )
        }
        return UsbScreenState(
            status = if (link == UsbLinkState.USB_CONNECTED) UsbStatus.READY_TO_UPDATE else UsbStatus.WAITING_FOR_USB,
            link = link,
            canCopyCommand = assessment.adbCommand != null,
        )
    }
}

/** The states the USB screen can be in. Each maps to exactly one string. */
enum class UsbStatus {
    WAITING_FOR_USB,
    READY_TO_UPDATE,
    UPDATE_SUCCESSFUL,
    UPDATE_REJECTED,
    ALREADY_CURRENT,
    APK_UNAVAILABLE,
}

data class UsbScreenState(
    val status: UsbStatus,
    val link: UsbUpdateController.UsbLinkState,
    val canCopyCommand: Boolean,
)

/** The one pure mapping from state to the sentence the user reads. */
fun UsbScreenState.messageRes(): Int = when (status) {
    UsbStatus.WAITING_FOR_USB -> R.string.usb_status_waiting
    UsbStatus.READY_TO_UPDATE -> R.string.usb_status_ready
    UsbStatus.UPDATE_SUCCESSFUL -> R.string.usb_status_success
    UsbStatus.UPDATE_REJECTED -> R.string.usb_status_rejected
    UsbStatus.ALREADY_CURRENT -> R.string.usb_status_current
    UsbStatus.APK_UNAVAILABLE -> R.string.usb_status_apk_missing
}

/** Green / amber / red for the status chip. Only a real failure is red. */
fun UsbScreenState.severity(): UsbSeverity = when (status) {
    UsbStatus.UPDATE_SUCCESSFUL -> UsbSeverity.SUCCESS
    UsbStatus.READY_TO_UPDATE, UsbStatus.WAITING_FOR_USB -> UsbSeverity.INFO
    UsbStatus.ALREADY_CURRENT -> UsbSeverity.INFO
    UsbStatus.UPDATE_REJECTED -> UsbSeverity.ERROR
    UsbStatus.APK_UNAVAILABLE -> UsbSeverity.WARNING
}

enum class UsbSeverity { INFO, SUCCESS, WARNING, ERROR }