package com.vishal.riy.update

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * JVM tests for the pure (Android-free) APK-download logic: file naming and the
 * temp -> final APK commit that happens only after a successful, verified download.
 */
class UpdateInstallerDownloadTest {

    @Test
    fun `apk file name is derived from the version`() {
        assertEquals("riy-v1.0.3.apk", UpdateInstaller.apkFileName("1.0.3"))
        assertEquals("riy-v2.0.0.apk", UpdateInstaller.apkFileName("2.0.0"))
    }

    @Test
    fun `commitApk renames the temp file to the final apk path`() {
        val dir = File(System.getProperty("java.io.tmpdir"), "riy-test-${System.nanoTime()}")
        dir.mkdirs()
        val temp = File(dir, "riy-v1.0.3.apk.part")
        temp.writeBytes("fake-apk-bytes".toByteArray())
        val final = File(dir, "riy-v1.0.3.apk")

        val result = UpdateInstaller.commitApk(temp, final)

        assertTrue(result.exists())
        assertEquals(final.absolutePath, result.absolutePath)
        assertFalse(temp.exists())
    }

    @Test
    fun `redirect statuses are detected`() {
        assertTrue(UpdateInstaller.isRedirectStatus(301))
        assertTrue(UpdateInstaller.isRedirectStatus(302))
        assertTrue(UpdateInstaller.isRedirectStatus(303))
        assertTrue(UpdateInstaller.isRedirectStatus(307))
        assertTrue(UpdateInstaller.isRedirectStatus(308))
        assertFalse(UpdateInstaller.isRedirectStatus(200))
        assertFalse(UpdateInstaller.isRedirectStatus(404))
        assertFalse(UpdateInstaller.isRedirectStatus(401))
    }

    @Test
    fun `trusted https github asset redirect is allowed`() {
        assertTrue(UpdateInstaller.isTrustedAssetRedirect("https://release-assets.githubusercontent.com/some-signed-url"))
        assertTrue(UpdateInstaller.isTrustedAssetRedirect("https://objects.githubusercontent.com/asset/123"))
    }

    @Test
    fun `untrusted or non-https asset redirect is rejected`() {
        assertFalse(UpdateInstaller.isTrustedAssetRedirect(null))
        assertFalse(UpdateInstaller.isTrustedAssetRedirect(""))
        assertFalse(UpdateInstaller.isTrustedAssetRedirect("http://release-assets.githubusercontent.com/abc")) // no https
        assertFalse(UpdateInstaller.isTrustedAssetRedirect("https://evil.example.com/abc")) // untrusted host
        assertFalse(UpdateInstaller.isTrustedAssetRedirect("https://api.github.com/repos/x/y/releases/assets/1")) // not CDN
        assertFalse(UpdateInstaller.isTrustedAssetRedirect("not a url"))
    }
}
