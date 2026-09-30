package com.vishal.riy.update

import org.json.JSONObject
import java.io.Serializable

/**
 * The lightweight metadata the app needs from the latest GitHub release:
 * versionName, derived versionCode and a validated APK download URL.
 *
 * SECURITY: [downloadUrl] is only accepted when it points at a release asset
 * inside the pinned repository (https + exact owner/repo allowlist), so no
 * arbitrary/untrusted URL can ever reach the installer.
 */
data class ReleaseInfo(
    val tagName: String,
    val versionName: String,
    val versionCode: Long,
    val assetId: Long,
    val downloadUrl: String,
    val releaseNotesUrl: String?,
) : Serializable {
    companion object {
        const val OWNER = "vishal507c-spec"
        const val REPO = "riy"
        private const val ALLOWED_URL_PREFIX = "https://github.com/$OWNER/$REPO/releases/download/"
        // Private-repo APK assets must be downloaded via the GitHub Releases Assets
        // API (browser_download_url returns 404 for private repositories). The URL is
        // built from the pinned owner/repo + a validated asset id, so it can never
        // point at an arbitrary host. Requires: Accept: application/octet-stream.
        private const val ASSET_API_URL_PREFIX = "https://api.github.com/repos/$OWNER/$REPO/releases/assets/"

        /**
         * Parses the JSON body of `GET /repos/{owner}/{repo}/releases/latest`.
         *
         * Rules:
         *  - Draft releases are rejected (the /latest endpoint never returns them,
         *    but we re-check defensively).
         *  - Pre-releases are rejected: only completed, stable releases are offered.
         *  - The first asset whose name ends in ".apk" (case-insensitive) and whose
         *    download URL is inside the pinned repo is selected; anything else is
         *    treated as malformed metadata and rejected (null).
         *  - tag_name must parse to a valid versionCode; otherwise null.
         */
        fun fromLatestReleaseJson(json: String): ReleaseInfo? {
            return try {
                val root = JSONObject(json)
                if (root.optBoolean("draft", false)) return null
                if (root.optBoolean("prerelease", false)) return null

                val tagName = root.optString("tag_name", "")
                val versionCode = VersionUtils.versionCodeFromName(tagName) ?: return null

                val assets = root.optJSONArray("assets") ?: return null
                for (i in 0 until assets.length()) {
                    val asset = assets.optJSONObject(i) ?: continue
                    val name = asset.optString("name", "")
                    val assetId = asset.optLong("id", -1L)
                    if (assetId <= 0L) continue // no id -> cannot build a safe API download URL
                    if (!name.endsWith(".apk", ignoreCase = true)) continue
                    // Keep repository allowlisting/security: the asset must be an APK
                    // whose browser_download_url is inside the pinned repo (HTTPS,
                    // exact owner/repo, no traversal/encoding tricks).
                    val url = asset.optString("browser_download_url", "")
                    if (!url.startsWith(ALLOWED_URL_PREFIX)) continue
                    if (url.contains("..") || url.contains("%")) continue
                    return ReleaseInfo(
                        tagName = tagName,
                        versionName = VersionUtils.stripTagPrefix(tagName),
                        versionCode = versionCode,
                        assetId = assetId,
                        downloadUrl = ASSET_API_URL_PREFIX + assetId,
                        releaseNotesUrl = root.optString("html_url", "").ifEmpty { null },
                    )
                }
                null // no valid APK asset in the release
            } catch (_: Exception) {
                null // malformed metadata -> update check simply fails gracefully
            }
        }
    }
}
