package com.vishal.riy.update

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Tests for parsing GitHub `/releases/latest` metadata, including the
 * malformed-metadata and untrusted-URL rejection cases.
 */
class ReleaseInfoTest {

    private fun releaseJson(
        tag: String = "v1.0.5",
        prerelease: Boolean = false,
        draft: Boolean = false,
        assetName: String = "riy-v1.0.5.apk",
        assetUrl: String = "https://github.com/vishal507c-spec/riy/releases/download/v1.0.5/riy-v1.0.5.apk",
        assetId: Long = 1001L,
    ): String {
        val asset = JSONObject()
            .put("name", assetName)
            .put("id", assetId)
            .put("browser_download_url", assetUrl)
        return JSONObject()
            .put("tag_name", tag)
            .put("draft", draft)
            .put("prerelease", prerelease)
            .put("html_url", "https://github.com/vishal507c-spec/riy/releases/tag/$tag")
            .put("assets", JSONArray().put(asset))
            .toString()
    }

    @Test
    fun `parses a valid release`() {
        val info = ReleaseInfo.fromLatestReleaseJson(releaseJson())!!
        assertEquals("1.0.5", info.versionName)
        assertEquals(1_000_005L, info.versionCode)
        assertEquals("v1.0.5", info.tagName)
        assertEquals(1001L, info.assetId)
        assertEquals(
            "https://api.github.com/repos/vishal507c-spec/riy/releases/assets/1001",
            info.downloadUrl,
        )
    }

    @Test
    fun `download url uses the github releases assets api endpoint`() {
        val info = ReleaseInfo.fromLatestReleaseJson(releaseJson(assetId = 5555L))!!
        assertEquals(
            "https://api.github.com/repos/vishal507c-spec/riy/releases/assets/5555",
            info.downloadUrl,
        )
    }

    @Test
    fun `asset without an id is rejected`() {
        val asset = JSONObject()
            .put("name", "riy-v1.0.5.apk")
            .put("browser_download_url", "https://github.com/vishal507c-spec/riy/releases/download/v1.0.5/riy-v1.0.5.apk")
        val json = JSONObject()
            .put("tag_name", "v1.0.5")
            .put("draft", false)
            .put("prerelease", false)
            .put("html_url", "https://github.com/vishal507c-spec/riy/releases/tag/v1.0.5")
            .put("assets", JSONArray().put(asset))
            .toString()
        assertNull(ReleaseInfo.fromLatestReleaseJson(json))
    }

    @Test
    fun `parses release 1_0_10 correctly - no lexicographic bug`() {
        val info = ReleaseInfo.fromLatestReleaseJson(releaseJson(tag = "v1.0.10", assetName = "riy-v1.0.10.apk"))!!
        assertEquals(1_000_010L, info.versionCode)
        assertEquals("1.0.10", info.versionName)
    }

    @Test
    fun `prerelease is rejected`() {
        assertNull(ReleaseInfo.fromLatestReleaseJson(releaseJson(prerelease = true)))
    }

    @Test
    fun `draft is rejected`() {
        assertNull(ReleaseInfo.fromLatestReleaseJson(releaseJson(draft = true)))
    }

    @Test
    fun `malformed json returns null`() {
        assertNull(ReleaseInfo.fromLatestReleaseJson("{ not json"))
        assertNull(ReleaseInfo.fromLatestReleaseJson(""))
    }

    @Test
    fun `missing or invalid tag returns null`() {
        assertNull(ReleaseInfo.fromLatestReleaseJson(releaseJson(tag = "")))
        assertNull(ReleaseInfo.fromLatestReleaseJson(releaseJson(tag = "not-a-version")))
    }

    @Test
    fun `asset url from a different repository is rejected`() {
        assertNull(
            ReleaseInfo.fromLatestReleaseJson(
                releaseJson(
                    assetUrl = "https://evil.example.com/apk/riy-v1.0.5.apk",
                ),
            ),
        )
    }

    @Test
    fun `asset url from another repo path is rejected`() {
        assertNull(
            ReleaseInfo.fromLatestReleaseJson(
                releaseJson(
                    assetUrl = "https://github.com/other-user/other-repo/releases/download/v1.0.5/app.apk",
                ),
            ),
        )
    }

    @Test
    fun `non-https asset url is rejected`() {
        assertNull(
            ReleaseInfo.fromLatestReleaseJson(
                releaseJson(
                    assetUrl = "http://github.com/vishal507c-spec/riy/releases/download/v1.0.5/riy-v1.0.5.apk",
                ),
            ),
        )
    }

    @Test
    fun `non-apk asset is skipped and release without apk asset returns null`() {
        assertNull(ReleaseInfo.fromLatestReleaseJson(releaseJson(assetName = "notes.txt")))
    }

    @Test
    fun `traversal-style url is rejected`() {
        assertNull(
            ReleaseInfo.fromLatestReleaseJson(
                releaseJson(
                    assetUrl = "https://github.com/vishal507c-spec/riy/releases/download/..%2Fevil/app.apk",
                ),
            ),
        )
    }
}
