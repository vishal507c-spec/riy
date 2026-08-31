package com.vishal.riy.update

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Test H — version comparison, including the lexicographic-bug cases.
 */
class VersionUtilsTest {

    @Test
    fun `1_0_9 is less than 1_0_10`() {
        assertTrue(VersionUtils.compareVersionNames("1.0.9", "1.0.10") < 0)
    }

    @Test
    fun `1_0_10 is greater than 1_0_9`() {
        assertTrue(VersionUtils.compareVersionNames("1.0.10", "1.0.9") > 0)
    }

    @Test
    fun `naive string comparison would be wrong - numeric comparison is not`() {
        // "1.10" < "1.9" lexicographically, but numerically 1.10 > 1.9.
        assertTrue("1.10" < "1.9") // documents the bug we must avoid
        assertTrue(VersionUtils.compareVersionNames("1.10.0", "1.9.0") > 0)
    }

    @Test
    fun `equal versions compare as zero`() {
        assertEquals(0, VersionUtils.compareVersionNames("1.0.5", "1.0.5"))
        assertEquals(0, VersionUtils.compareVersionNames("v1.0.5", "1.0.5"))
    }

    @Test
    fun `tag prefix is stripped`() {
        assertEquals("1.0.5", VersionUtils.stripTagPrefix("v1.0.5"))
        assertEquals("1.0.5", VersionUtils.stripTagPrefix("V1.0.5"))
        assertEquals("1.0.5", VersionUtils.stripTagPrefix("1.0.5"))
    }

    @Test
    fun `versionCode mapping is numeric and monotonic`() {
        assertEquals(1_000_000L, VersionUtils.versionCodeFromName("1.0.0"))
        assertEquals(1_000_009L, VersionUtils.versionCodeFromName("1.0.9"))
        assertEquals(1_000_010L, VersionUtils.versionCodeFromName("v1.0.10"))
        assertEquals(2_000_000L, VersionUtils.versionCodeFromName("2.0.0"))
        assertEquals(1_010_000L, VersionUtils.versionCodeFromName("1.10.0"))
        // 1.0.10 must have a strictly greater code than 1.0.9
        assertTrue(VersionUtils.versionCodeFromName("1.0.10")!! > VersionUtils.versionCodeFromName("1.0.9")!!)
    }

    @Test
    fun `malformed versions return null`() {
        assertNull(VersionUtils.versionCodeFromName("1.0"))
        assertNull(VersionUtils.versionCodeFromName("abc"))
        assertNull(VersionUtils.versionCodeFromName("1.0.x"))
        assertNull(VersionUtils.versionCodeFromName("1.0.1000")) // segment overflow guard
        assertNull(VersionUtils.versionCodeFromName(""))
    }

    @Test
    fun `four-segment names still order correctly`() {
        assertTrue(VersionUtils.compareVersionNames("1.0.0.1", "1.0.0") > 0)
        assertTrue(VersionUtils.compareVersionNames("1.0.0", "1.0.0.1") < 0)
    }
}
