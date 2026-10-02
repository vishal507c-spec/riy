package com.vishal.riy.update

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.util.Log
import com.vishal.riy.R
import com.vishal.riy.admin.RiyDeviceAdminReceiver
import com.vishal.riy.protection.enforcement.platform.AdminComponent
import com.vishal.riy.protection.enforcement.platform.AndroidDevicePolicyBoundary
import com.vishal.riy.protection.enforcement.platform.DevicePolicyBoundary
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/**
 * Owns everything about downloading and handing the APK to the Android package
 * installer. The download is app-controlled (no DownloadManager dependency):
 *
 *  - The private APK is fetched from the GitHub Releases Assets API URL (built and
 *    validated by [ReleaseInfo]: HTTPS, pinned owner/repo, allow-listed, no
 *    arbitrary URLs). Authenticated with the existing token plus
 *    `Accept: application/octet-stream`; the token/header is never logged.
 *  - The API responds with a 302 redirect to a trusted GitHub asset host; that redirect
 *    is followed ONLY to an HTTPS host in [TRUSTED_CDN_HOSTS], and the GitHub API
 *    Bearer token is NOT re-sent to the CDN (its URL carries its own credentials).
 *  - Downloaded into app-private storage to a temp file first; only moved
 *    ([commitApk]) to the final APK path after the download completes with the
 *    expected size.
 *  - Before installation the APK is verified with [verifyApk]: it must be a
 *    parseable package, match our own package name and never be a downgrade.
 *    Android then enforces the signature check at install time.
 *  - All network/file work runs on [Dispatchers.IO]; the main thread never does I/O.
 */
object UpdateInstaller {

    private const val TAG = "RiyUpdateInstall"
    private const val CONNECT_TIMEOUT_MS = 15_000
    private const val READ_TIMEOUT_MS = 20_000
    private const val MAX_REDIRECTS = 5

    /** HTTP status codes treated as a redirect that must be followed to a trusted host. */
    private val REDIRECT_CODES = setOf(301, 302, 303, 307, 308)

    /**
     * Only these trusted, GitHub-owned HTTPS asset hosts may be followed after the
     * GitHub Releases Assets API 302 redirect. The redirect URL carries its own
     * signed credentials; the API Bearer token is never sent to these hosts.
     */
    internal val TRUSTED_CDN_HOSTS = setOf(
        "release-assets.githubusercontent.com",
        "objects.githubusercontent.com",
    )

    /** Pure, testable: is [code] a redirect status we should follow? */
    internal fun isRedirectStatus(code: Int): Boolean = code in REDIRECT_CODES

    /**
     * Pure, testable: is [location] a safe (HTTPS, trusted GitHub asset host) redirect
     * target? Blank/malformed/non-HTTPS/untrusted-host targets are rejected.
     */
    internal fun isTrustedAssetRedirect(location: String?): Boolean {
        if (location.isNullOrBlank()) return false
        val uri = try { java.net.URI(location) } catch (_: Exception) { return false }
        return uri.scheme == "https" &&
            !uri.host.isNullOrBlank() &&
            uri.host in TRUSTED_CDN_HOSTS
    }

    /** App-private directory that holds the downloaded/final APK. */
    fun apkDir(context: Context): File = File(context.filesDir, "apk").apply { mkdirs() }

    fun apkFileName(versionName: String): String = "riy-v$versionName.apk"

    /** Final destination path for the APK. */
    fun apkFile(context: Context, info: ReleaseInfo): File =
        File(apkDir(context), apkFileName(info.versionName))

    /**
     * Downloads the private APK from the allow-listed Assets API URL into a temp
     * file in app-private storage, streaming the body and reporting [onProgress].
     * Returns the temp [File] only if the download completed fully (HTTP 200 and,
     * when a content length is known, the exact byte count). Throws [IOException]
     * on HTTP errors, connection failures, timeouts or an incomplete download, and
     * deletes the temp file (and fails) on cancellation. It never writes the final
     * APK path — that only happens via [commitApk] after a successful, verified
     * download.
     */
    suspend fun downloadToTemp(
        context: Context,
        info: ReleaseInfo,
        onProgress: suspend (downloaded: Long, total: Long) -> Unit,
    ): File = withContext(Dispatchers.IO) {
        var url: String = info.downloadUrl
        // Authorization is sent ONLY for the pinned GitHub API request; once we have
        // been redirected to a trusted GitHub asset CDN we NEVER send the Bearer token
        // there (the redirected URL carries its own signed credentials).
        var sendAuth = true
        var redirects = 0
        var done = false
        val tmp = File(apkDir(context), "${apkFileName(info.versionName)}.part")
        tmp.delete() // discard any partial from a previous attempt
        try {
            while (!done) {
                val connection = URL(url).openConnection() as HttpURLConnection
                try {
                    connection.requestMethod = "GET"
                    connection.connectTimeout = CONNECT_TIMEOUT_MS
                    connection.readTimeout = READ_TIMEOUT_MS
                    connection.setRequestProperty("Accept", "application/octet-stream")
                    if (sendAuth) {
                        UpdateToken.value?.let { connection.setRequestProperty("Authorization", "Bearer $it") }
                    }
                    connection.instanceFollowRedirects = false

                    val code = connection.responseCode
                    if (isRedirectStatus(code)) {
                        if (redirects >= MAX_REDIRECTS) throw IOException("Too many redirects (> $MAX_REDIRECTS)")
                        val location = connection.getHeaderField("Location") ?: throw IOException("Missing redirect Location")
                        val destination = URL(URL(url), location)
                        if (!isTrustedAssetRedirect(destination.toExternalForm())) {
                            throw IOException("Untrusted redirect: ${destination.host}")
                        }
                        url = destination.toExternalForm()
                        sendAuth = false // never send the GitHub API token to the CDN
                        redirects++
                        continue
                    }
                    if (code != HttpURLConnection.HTTP_OK) {
                        throw IOException("HTTP $code from releases assets API")
                    }

                    val total = connection.contentLengthLong
                    tmp.outputStream().use { out ->
                        connection.inputStream.use { input ->
                            val buffer = ByteArray(128 * 1024)
                            var downloaded: Long = 0L
                            while (true) {
                                val read = input.read(buffer)
                                if (read == -1) break
                                ensureActive() // cancels cleanly if the fragment is destroyed
                                out.write(buffer, 0, read)
                                downloaded += read
                                onProgress(downloaded, total)
                            }
                            if (total > 0L && downloaded != total) {
                                throw IOException("Incomplete APK download ($downloaded/$total bytes)")
                            }
                            if (downloaded == 0L) throw IOException("Empty APK response")
                        }
                    }
                    done = true
                } finally {
                    connection.disconnect()
                }
            }
            tmp
        } catch (e: Exception) {
            runCatching { tmp.delete() }
            throw e
        }
    }

    /**
     * Moves a fully-downloaded (and verified) temp APK to its final path. Falls
     * back to copy+delete if a direct rename is not possible.
     */
    fun commitApk(temp: File, final: File): File {
        if (temp.renameTo(final)) return final
        temp.copyTo(final, overwrite = true)
        temp.delete()
        return final
    }

    fun canRequestInstalls(context: Context): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.O || context.packageManager.canRequestPackageInstalls()

    /** Opens the system screen where the user grants "install unknown apps". */
    fun installPermissionIntent(context: Context): Intent =
        Intent(android.provider.Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES)
            .setData(Uri.parse("package:${context.packageName}"))

    /**
     * Verifies a (downloaded) APK before installation:
     *  1. it is a parseable Android package;
     *  2. its package name equals our own applicationId;
     *  3. its versionCode is >= the installed one (never a downgrade).
     * Signature identity is enforced by the OS during installation.
     */
    fun verifyApk(context: Context, apkFile: File, installedVersionCode: Long): Boolean {
        if (!apkFile.exists() || apkFile.length() == 0L) return false
        val pm = context.packageManager
        val info = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            pm.getPackageArchiveInfo(apkFile.absolutePath, android.content.pm.PackageManager.PackageInfoFlags.of(0))
        } else {
            @Suppress("DEPRECATION")
            pm.getPackageArchiveInfo(apkFile.absolutePath, 0)
        } ?: return false
        if (info.packageName != context.packageName) return false
        val apkVersionCode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) info.longVersionCode else info.versionCode.toLong()
        return apkVersionCode >= installedVersionCode
    }

    /**
     * Self-update exemption. RIY's own Device Owner hardening raises
     * `no_install_unknown_sources` while protection is active — which the
     * system installer reports as "Blocked by your IT admin", including for
     * RIY's own verified update (a per-app "install unknown apps" grant can
     * never override a Device Owner restriction).
     *
     * So immediately before handing OUR verified APK to the system installer,
     * a Device Owner RIY lifts ONLY that one restriction for its own update.
     * Scope is deliberately narrow (unknown-sources only; Private-DNS and
     * VPN-config restrictions stay), and the window closes itself: the next
     * foreground/boot/package reconciliation re-raises it while protection is
     * wanted, or the live restriction re-hardens it. Non-owners are a safe
     * no-op (false). Never throws.
     *
     * @return true when the exemption was applied (Device Owner only).
     */
    fun allowSelfUpdateInstall(context: Context): Boolean {
        return try {
            val app = context.applicationContext
            val devicePolicy = AndroidDevicePolicyBoundary(app)
            if (!devicePolicy.isDeviceOwnerApp(app.packageName)) return false
            val admin = AdminComponent(
                app.packageName,
                RiyDeviceAdminReceiver::class.java.name,
            )
            val cleared = devicePolicy.clearUserRestriction(
                admin,
                DevicePolicyBoundary.RESTRICTION_INSTALL_UNKNOWN_SOURCES,
            )
            Log.i(TAG, "self-update exemption applied=$cleared")
            cleared
        } catch (e: Exception) {
            Log.w(TAG, "self-update exemption failed: ${e.javaClass.simpleName}")
            false
        }
    }

    /** Intent that hands the verified APK to the system installer. */
    fun buildInstallIntent(context: Context, apkFile: File): Intent {
        val uri = androidx.core.content.FileProvider.getUriForFile(
            context,
            "${context.packageName}.fileprovider",
            apkFile,
        )
        return Intent(Intent.ACTION_INSTALL_PACKAGE).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
    }
}
