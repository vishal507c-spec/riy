package com.vishal.riy.update

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The USB screen's honesty.
 *
 * The rule under test: the app never claims more than it can actually know. It
 * cannot see ADB authorisation (that lives on the computer), so it must never
 * say "device detected"; and "update successful" may only ever come from the
 * installed version having actually reached the release version.
 */
class UsbUpdateStateTest {

    private val installable = ApkAssessment(
        disposition = ApkDisposition.INSTALLABLE,
        adbCommand = "adb install -r \"riy-v2.7.0.apk\"",
    )
    private val rejected = ApkAssessment(
        disposition = ApkDisposition.REJECTED,
        rejection = ApkRejection.SIGNATURE_MISMATCH,
        messageRes = com.vishal.riy.R.string.update_reject_signature,
    )

    @Test
    fun `no USB device attached shows waiting, not a failure`() {
        val state = state(link = UsbUpdateController.UsbLinkState.USB_NOT_CONNECTED)

        assertEquals(UsbStatus.WAITING_FOR_USB, state.status)
        assertEquals(UsbSeverity.INFO, state.severity())
    }

    @Test
    fun `a USB device attached shows ready to update`() {
        val state = state(link = UsbUpdateController.UsbLinkState.USB_CONNECTED)

        assertEquals(UsbStatus.READY_TO_UPDATE, state.status)
        assertTrue(state.canCopyCommand)
    }

    @Test
    fun `a finished update is reported from the installed version, never guessed`() {
        val state = state(installedVersionCode = 2_007_000L, releaseVersionCode = 2_007_000L)

        assertEquals(UsbStatus.UPDATE_SUCCESSFUL, state.status)
        assertEquals(UsbSeverity.SUCCESS, state.severity())
    }

    @Test
    fun `still on the old version is NOT reported as success`() {
        val state = state(installedVersionCode = 2_006_000L, releaseVersionCode = 2_007_000L)

        assertFalse(state.status == UsbStatus.UPDATE_SUCCESSFUL)
    }

    @Test
    fun `a rejected APK is the only red state and offers no command`() {
        val state = state(assessment = rejected)

        assertEquals(UsbStatus.UPDATE_REJECTED, state.status)
        assertEquals(UsbSeverity.ERROR, state.severity())
        assertFalse("no command for a rejected update", state.canCopyCommand)
    }

    @Test
    fun `a missing staged APK is a warning, not a scary failure`() {
        val state = state(staging = UsbUpdateController.Staging.Failed("gone"))

        assertEquals(UsbStatus.APK_UNAVAILABLE, state.status)
        assertEquals(UsbSeverity.WARNING, state.severity())
    }

    @Test
    fun `an already current version needs no action`() {
        val assessment = ApkAssessment(disposition = ApkDisposition.ALREADY_CURRENT)
        val state = state(assessment = assessment, installedVersionCode = 2_007_000L, releaseVersionCode = 2_008_000L)

        assertEquals(UsbStatus.ALREADY_CURRENT, state.status)
        assertFalse(state.canCopyCommand)
    }

    @Test
    fun `success outranks a rejected assessment on the next launch`() {
        // After a successful ADB install the fragment re-reads a stale verdict;
        // the observed installed version must win.
        val state = state(
            assessment = rejected,
            installedVersionCode = 2_007_000L,
            releaseVersionCode = 2_007_000L,
        )

        assertEquals(UsbStatus.UPDATE_SUCCESSFUL, state.status)
    }

    @Test
    fun `every status maps to a real string`() {
        val all = listOf(
            UsbStatus.WAITING_FOR_USB, UsbStatus.READY_TO_UPDATE, UsbStatus.UPDATE_SUCCESSFUL,
            UsbStatus.UPDATE_REJECTED, UsbStatus.ALREADY_CURRENT, UsbStatus.APK_UNAVAILABLE,
        )

        assertEquals(
            all.size,
            all.map { status ->
                UsbScreenState(status, UsbUpdateController.UsbLinkState.USB_NOT_CONNECTED, false).messageRes()
            }.distinct().size,
        )
        all.forEach { status ->
            val message = UsbScreenState(
                status,
                UsbUpdateController.UsbLinkState.USB_NOT_CONNECTED,
                false,
            ).messageRes()
            assertTrue(message != 0)
        }
    }

    @Test
    fun `red is reserved for an actual failure`() {
        val severities = UsbStatus.entries.associateWith { status ->
            UsbScreenState(status, UsbUpdateController.UsbLinkState.USB_NOT_CONNECTED, false).severity()
        }

        assertEquals(
            setOf(UsbStatus.UPDATE_REJECTED),
            severities.filterValues { it == UsbSeverity.ERROR }.keys,
        )
        assertEquals(UsbSeverity.SUCCESS, severities[UsbStatus.UPDATE_SUCCESSFUL])
        assertEquals(UsbSeverity.WARNING, severities[UsbStatus.APK_UNAVAILABLE])
    }

    @Test
    fun `the USB controller never needs a hidden UsbManager class`() {
        // Guards the fix for a real compile failure: android.hardware.usb.UsbManager
        // is not public SDK, so its absence here is deliberate.
        val source = UsbUpdateController::class.java.methods.map { it.returnType.name }
        assertFalse(source.any { it.contains("UsbManager") })
    }

    @Test
    fun `the staging names are stable strings that survive obfuscation`() {
        assertTrue(UsbUpdateController.STAGING_EXPORTED.isNotBlank())
        assertTrue(UsbUpdateController.STAGING_PC_DOWNLOAD.isNotBlank())
        assertTrue(UsbUpdateController.STAGING_FAILED.isNotBlank())
        assertEquals(
            3,
            listOf(
                UsbUpdateController.STAGING_EXPORTED,
                UsbUpdateController.STAGING_PC_DOWNLOAD,
                UsbUpdateController.STAGING_FAILED,
            ).distinct().size,
        )
    }

    @Test
    fun `the exported location is a readable path on the phone`() {
        val location = UsbUpdateController.exportedLocation("riy-v2.7.0.apk")

        assertTrue(location.startsWith("/sdcard/Download/"))
        assertTrue(location.endsWith("riy-v2.7.0.apk"))
    }

    // ------------------------------------------------------------- helpers

    private fun state(
        link: UsbUpdateController.UsbLinkState = UsbUpdateController.UsbLinkState.USB_NOT_CONNECTED,
        assessment: ApkAssessment = installable,
        installedVersionCode: Long = 2_006_000L,
        releaseVersionCode: Long = 2_007_000L,
        staging: UsbUpdateController.Staging = UsbUpdateController.Staging.Exported("riy-v2.7.0.apk"),
    ) = UsbUpdateController.screenState(
        link = link,
        assessment = assessment,
        installedVersionCode = installedVersionCode,
        releaseVersionCode = releaseVersionCode,
        staging = staging,
    )
}