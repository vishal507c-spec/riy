package com.vishal.riy.update

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
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
 *  - Before installation the APK is verified by [ApkCompatibility] +
 *    [InstallEnvironmentProbe]: it must be a parseable package, match our own
 *    package name, not be a downgrade, and carry the SAME signing certificate
 *    as the installed app. Android then re-checks the signature itself.
 *  - Installation is performed by [PackageInstallerLauncher] using a real
 *    `PackageInstaller` session, so success / cancel / policy-block / failure
 *    are all reported instead of guessed.
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

    // ---------------------------------------------------------------------
    // DELEGATED ON PURPOSE.
    //
    // APK verification used to live here as a weak check (parseable, right
    // package, not a downgrade). It now lives in [ApkCompatibility] +
    // [InstallEnvironmentProbe], which additionally verify the SIGNING
    // CERTIFICATE against the installed app before anything is installed, and
    // it returns a decision + reason instead of a bare boolean. Keeping an
    // older, weaker verifier next to it would invite a future caller to use the
    // wrong one, so it was removed rather than left dormant.
    //
    // Installation itself is likewise delegated to [PackageInstallerLauncher]
    // (a real session API with a real result) instead of the deprecated
    // ACTION_INSTALL_PACKAGE intent.
}
