package com.vishal.riy.update

import com.vishal.riy.R
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * APK verification before anything is installed.
 *
 * This is the safety net for the USB/ADB path: every edge case the flow can hit
 * (missing, corrupt, wrong package, older, identical, differently signed) has to
 * produce a decided, explained outcome — and a REJECTED APK must never come with
 * a command that would destroy the user's data.
 */
class ApkCompatibilityTest {

    private val installed = ApkFacts(
        packageName = ApkCompatibility.EXPECTED_PACKAGE,
        versionCode = 2_007_000L,
        versionName = "2.7.0",
        signerSha256 = CERT,
        readable = true,
    )

    // ---------------------------------------------------------------- happy path

    @Test
    fun `a correctly signed newer update is installable in place`() {
        val assessment = assess(candidate(versionCode = 2_008_000L, versionName = "2.8.0"))

        assertEquals(ApkDisposition.INSTALLABLE, assessment.disposition)
        assertNull(assessment.rejection)
        assertEquals("adb install -r \"riy-v2.8.0.apk\"", assessment.adbCommand)
        assertEquals(R.string.update_ready_usb, assessment.messageRes)
    }

    @Test
    fun `the update command is an in-place install that keeps app data`() {
        val command = ApkCompatibility.assess(
            installed,
            candidate(versionCode = 2_008_000L),
            "riy-v2.8.0.apk",
        ).adbCommand!!

        // -r replaces the package WITHOUT uninstalling it, which is exactly
        // what preserves preferences, blocker config and accessibility state.
        assertTrue("must use -r", command.contains("install -r"))
        assertFalse("must never uninstall", command.contains("uninstall"))
        assertFalse("must never clear data", command.contains("pm clear"))
    }

    // ------------------------------------------------------------- edge cases

    @Test
    fun `a missing file is rejected and explained`() {
        val assessment = ApkCompatibility.assess(installed, ApkFacts.UNREADABLE, "x.apk")

        assertEquals(ApkDisposition.REJECTED, assessment.disposition)
        assertEquals(ApkRejection.MISSING, assessment.rejection)
        assertEquals(R.string.update_reject_missing, assessment.messageRes)
        assertNull(assessment.adbCommand)
    }

    @Test
    fun `a zero length file is rejected as missing`() {
        val assessment = assess(candidate().copy(readable = true, sizeBytes = 0L))

        assertEquals(ApkDisposition.REJECTED, assessment.disposition)
        assertEquals(ApkRejection.MISSING, assessment.rejection)
    }

    @Test
    fun `a corrupt archive is rejected and explained`() {
        val assessment = ApkCompatibility.assess(
            installed,
            candidate().copy(readable = false),
            "x.apk",
        )

        assertEquals(ApkRejection.CORRUPT, assessment.rejection)
        assertEquals(R.string.update_reject_corrupt, assessment.messageRes)
        assertNull(assessment.adbCommand)
    }

    @Test
    fun `an unrelated package is refused so no foreign APK is ever installed`() {
        val assessment = assess(candidate().copy(packageName = "com.some.other.app"))

        assertEquals(ApkRejection.WRONG_PACKAGE, assessment.rejection)
        assertEquals(R.string.update_reject_wrong_package, assessment.messageRes)
        assertNull("no command for a foreign package", assessment.adbCommand)
    }

    @Test
    fun `a different signing certificate is a hard stop with no bypass offered`() {
        val assessment = assess(candidate(signer = "deadbeef"))

        assertEquals(ApkDisposition.REJECTED, assessment.disposition)
        assertEquals(ApkRejection.SIGNATURE_MISMATCH, assessment.rejection)
        assertEquals(R.string.update_reject_signature, assessment.messageRes)
        // The only thing that would "fix" this is an uninstall, which destroys
        // user data — so no command is produced at all.
        assertNull(assessment.adbCommand)
    }

    @Test
    fun `an unreadable signature is never treated as a match`() {
        val assessment = assess(candidate(signer = null))

        assertEquals(ApkRejection.SIGNATURE_MISMATCH, assessment.rejection)
        assertNull(assessment.adbCommand)
    }

    @Test
    fun `the same version is not an update`() {
        val assessment = assess(candidate(versionCode = 2_007_000L, versionName = "2.7.0"))

        assertEquals(ApkDisposition.ALREADY_CURRENT, assessment.disposition)
        assertEquals(R.string.update_reject_same_version, assessment.messageRes)
        assertNull(assessment.adbCommand)
    }

    @Test
    fun `an older version is rejected without an install command`() {
        val assessment = ApkCompatibility.assess(
            installed,
            candidate(versionCode = 2_006_000L, versionName = "2.6.0"),
            "riy-v2.6.0.apk",
        )

        assertEquals(ApkDisposition.REJECTED, assessment.disposition)
        assertEquals(ApkRejection.DOWNGRADE, assessment.rejection)
        assertEquals(R.string.update_reject_downgrade, assessment.messageRes)
        // A downgrade flag must not be supplied implicitly.
        assertNull(assessment.adbCommand)
    }

    @Test
    fun `every rejection produces the exact required signature message`() {
        assertEquals(
            R.string.update_reject_signature,
            assess(candidate(signer = "other")).messageRes,
        )
    }

    @Test
    fun `no rejected case ever hands out an uninstall command`() {
        val candidates = listOf(
            candidate(signer = "other"),
            candidate().copy(packageName = "com.evil.app"),
            candidate(versionCode = 1L),
            ApkFacts.UNREADABLE,
            candidate().copy(readable = false),
        )

        candidates.forEach { c ->
            val command = ApkCompatibility.assess(installed, c, "x.apk").adbCommand
            assertNull("a rejected APK must never carry a command, got '$command'", command)
        }
    }

    @Test
    fun `the pull command targets the exported file in Downloads`() {
        assertEquals(
            "adb pull \"/sdcard/Download/Riy/riy-v2.7.0.apk\" \"riy-v2.7.0.apk\"",
            ApkCompatibility.pullCommand("Riy/riy-v2.7.0.apk", "riy-v2.7.0.apk"),
        )
    }

    @Test
    fun `commands are quoted so a path with spaces can never break them`() {
        val command = ApkCompatibility.installCommand("my riy build.apk")

        assertTrue(command.startsWith("adb install -r \""))
        assertTrue(command.endsWith("\""))
    }

    @Test
    fun `verification depends on the installed package name so a foreign host cannot sneak in`() {
        val assessment = ApkCompatibility.assess(
            installed.copy(packageName = "com.vishal.riy"),
            candidate(versionCode = 9_999_999L),
            "x.apk",
        )

        assertEquals(ApkDisposition.INSTALLABLE, assessment.disposition)
        assertEquals(ApkCompatibility.EXPECTED_PACKAGE, installed.packageName)
    }

    // -------------------------------------------------------------- helpers

    private fun assess(candidateFacts: ApkFacts) =
        ApkCompatibility.assess(installed, candidateFacts, "riy-v2.8.0.apk")

    private fun candidate(
        versionCode: Long = 2_008_000L,
        versionName: String = "2.8.0",
        signer: String? = CERT,
    ) = ApkFacts(
        packageName = ApkCompatibility.EXPECTED_PACKAGE,
        versionCode = versionCode,
        versionName = versionName,
        signerSha256 = signer,
        readable = true,
        sizeBytes = 14_518_480L,
    )

    private companion object {
        const val CERT = "9929e687fcd18a8a8d1435cf2a866c57f419d77ce8efe1ecc9f95beac40411ed"
    }
}