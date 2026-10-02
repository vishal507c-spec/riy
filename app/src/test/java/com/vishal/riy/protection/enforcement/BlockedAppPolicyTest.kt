package com.vishal.riy.protection.enforcement

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Regression tests for the Telegram-allowed / TeraBox-blocked policy.
 *
 * Covers acceptance Tests A (Telegram usable), B/C (TeraBox blocked incl.
 * install-after-protection), D (unauthorized app blocked), I (RIY self-update
 * exempt). No message/file content is ever inspected — verdicts use only
 * package identity and install metadata.
 */
class BlockedAppPolicyTest {

    // ------------------------------------------------------------- TELEGRAM

    @Test
    fun `telegram official package is an allow-candidate, never blocked`() {
        assertTrue(BlockedAppPolicy.isTelegramPackage("org.telegram.messenger"))
        assertFalse(BlockedAppPolicy.isBlockedPackage("org.telegram.messenger", "Telegram"))
        assertEquals(null, BlockedAppPolicy.blockedCategoryFor("org.telegram.messenger"))
    }

    @Test
    fun `telegram forks are allow-candidates, never blocked`() {
        listOf("org.telegram.plus", "org.thunderdog.challegram", "nekox.messenger").forEach { pkg ->
            assertTrue("expected allow-candidate: $pkg", BlockedAppPolicy.isTelegramPackage(pkg))
            assertFalse(BlockedAppPolicy.isBlockedPackage(pkg))
        }
    }

    @Test
    fun `terabox is never a telegram package`() {
        assertFalse(BlockedAppPolicy.isTelegramPackage("com.flextech.client.terabox"))
    }

    // ------------------------------------------------------------- TERABOX

    @Test
    fun `terabox known identities are blocked by exact package`() {
        listOf(
            "com.flextech.client.terabox",
            "com.dubox.drive",
            "com.terabox.app",
            "jp.co.flextech.terabox",
        ).forEach { pkg ->
            assertTrue("expected blocked: $pkg", BlockedAppPolicy.isBlockedPackage(pkg))
            assertEquals(
                BlockedAppPolicy.BlockedCategory.TERABOX_CLOUD_BYPASS,
                BlockedAppPolicy.blockedCategoryFor(pkg),
            )
        }
    }

    @Test
    fun `terabox repackaged variant caught by package-substring metadata`() {
        // Not in the known list, but the package name carries the family hint.
        assertTrue(BlockedAppPolicy.isBlockedPackage("com.evil.terabox.clone"))
        assertTrue(BlockedAppPolicy.isBlockedPackage("com.clone.DUBOX.share"))
    }

    @Test
    fun `terabox detected by label metadata when package is obfuscated`() {
        assertTrue(BlockedAppPolicy.isBlockedPackage("com.obfuscated.filebox", "TeraBox Cloud"))
        assertTrue(BlockedAppPolicy.isBlockedPackage("com.obfuscated.x", "dubox drive"))
    }

    @Test
    fun `innocent file apps are not caught by terabox heuristics`() {
        // Google Drive (used by RIY backup) must stay untouched.
        assertFalse(BlockedAppPolicy.isBlockedPackage("com.google.android.apps.docs", "Drive"))
        assertFalse(BlockedAppPolicy.isBlockedPackage("com.example.distraction", "Distraction"))
    }

    // ------------------------------------------------------- UPDATE SAFETY

    @Test
    fun `riy self package is always exempt`() {
        val riy = "com.vishal.riy"
        assertTrue(BlockedAppPolicy.isSelfPackage(riy, riy))
        assertFalse(BlockedAppPolicy.isSelfPackage("com.flextech.client.terabox", riy))
    }

    // ------------------------------------------------------- BYPASS DOMAINS

    @Test
    fun `terabox cdn domains are bypass-transport blocked`() {
        assertTrue(BlockedAppPolicy.isBypassDomain("terabox.com"))
        assertTrue(BlockedAppPolicy.isBypassDomain("dl.terabox.com"))
        assertTrue(BlockedAppPolicy.isBypassDomain("dubox.com"))
    }

    @Test
    fun `encrypted dns endpoints are bypass-transport blocked`() {
        assertTrue(BlockedAppPolicy.isBypassDomain("dns.google"))
        assertTrue(BlockedAppPolicy.isBypassDomain("cloudflare-dns.com"))
        assertTrue(BlockedAppPolicy.isBypassDomain("one.one.one.one"))
    }

    @Test
    fun `telegram and legitimate domains are not bypass-blocked`() {
        assertFalse(BlockedAppPolicy.isBypassDomain("telegram.org"))
        assertFalse(BlockedAppPolicy.isBypassDomain("t.me"))
        assertFalse(BlockedAppPolicy.isBypassDomain("www.google.com"))
        assertFalse(BlockedAppPolicy.isBypassDomain(null))
        assertFalse(BlockedAppPolicy.isBypassDomain(""))
    }
}
