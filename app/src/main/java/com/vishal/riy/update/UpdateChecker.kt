package com.vishal.riy.update

import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/** TEMPORARY diagnostic tag; never contains credentials. */
private const val LOG_TAG = "RiyUpdate"

/**
 * Performs the single lightweight network request of the whole feature:
 * `GET https://api.github.com/repos/{owner}/{repo}/releases/latest`.
 *
 * Design properties:
 *  - One HTTPS GET per check (a few KB of JSON), never the repo, never polling.
 *  - Always executed on Dispatchers.IO; the UI thread is never blocked.
 *  - Every failure mode (no internet, timeout, HTTP 404/403/5xx, malformed
 *    JSON, missing release/asset) results in `null` — the app continues
 *    normally and update checking is never a single point of failure.
 */
object UpdateChecker {

    private const val API_URL =
        "https://api.github.com/repos/${ReleaseInfo.OWNER}/${ReleaseInfo.REPO}/releases/latest"
    private const val CONNECT_TIMEOUT_MS = 10_000
    private const val READ_TIMEOUT_MS = 10_000

    /**
     * @param currentVersionCode the installed app's versionCode.
     * @return [ReleaseInfo] for a strictly newer release, or null when there is
     *         no update or the check could not be completed.
     */
    suspend fun checkForUpdate(currentVersionCode: Long): ReleaseInfo? {
        Log.i(LOG_TAG, "checkForUpdate; installedCode=$currentVersionCode")
        val latest = fetchLatestRelease()
        if (latest == null) {
            Log.w(LOG_TAG, "no latest release metadata returned by GitHub")
            return null
        }
        val newer = latest.versionCode > currentVersionCode
        Log.i(LOG_TAG, "comparison: latest=${latest.versionCode} installed=$currentVersionCode newer=$newer")
        return if (newer) latest else null
    }

    private suspend fun fetchLatestRelease(): ReleaseInfo? = withContext(Dispatchers.IO) {
        // auth presence logged as boolean ONLY; never the token/header.
        Log.i(LOG_TAG, "HTTP request started url=$API_URL authTokenPresent=${UpdateToken.isConfigured}")
        try {
            val connection = URL(API_URL).openConnection() as HttpURLConnection
            try {
                connection.requestMethod = "GET"
                connection.connectTimeout = CONNECT_TIMEOUT_MS
                connection.readTimeout = READ_TIMEOUT_MS
                connection.setRequestProperty("Accept", "application/vnd.github+json")
                connection.setRequestProperty("User-Agent", "riy-app-updater")
                // Authenticated access to the PRIVATE repo. No header => public/anonymous
                // request => private repo returns 401/403 and the check no-ops gracefully.
                UpdateToken.value?.let {
                    connection.setRequestProperty("Authorization", "Bearer $it")
                }
                connection.instanceFollowRedirects = true

                val code = connection.responseCode
                Log.i(LOG_TAG, "HTTP response code=$code")
                if (code != HttpURLConnection.HTTP_OK) {
                    // 404 = no releases yet, 403 = rate limited, 5xx = GitHub down,
                    // 401/403 = auth failed (no valid token / insufficient scope).
                    Log.w(LOG_TAG, "non-200 HTTP response (code=$code); treating as no update")
                    return@withContext null
                }

                val body = connection.inputStream.bufferedReader().use { it.readText() }
                val parsed = ReleaseInfo.fromLatestReleaseJson(body)
                if (parsed == null) {
                    Log.w(LOG_TAG, "response parse FAILED (missing/invalid release or asset)")
                } else {
                    Log.i(LOG_TAG, "response parsed OK: v${parsed.versionName} code=${parsed.versionCode}")
                }
                parsed
            } finally {
                connection.disconnect()
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // IOException / JSONException / anything else -> graceful null.
            // Log type + message only; never headers/credentials.
            Log.e(LOG_TAG, "HTTP request threw: ${e.javaClass.simpleName}: ${e.message}", e)
            null
        }
    }
}
