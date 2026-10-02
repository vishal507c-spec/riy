package com.vishal.riy.blocker

import com.vishal.riy.protection.enforcement.BlockedAppPolicy
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Network-layer regression tests (acceptance E–F):
 *
 *  E. Known adult domains stay BLOCKED at DNS.
 *  F. Bypass transports (TeraBox CDN, encrypted-DNS endpoints) are sinkholed
 *     so alternate-DNS / DoH routes fall back to the filtered resolver —
 *     while Telegram and legitimate browsing stay reachable.
 *
 * Honest scope: DNS filtering removes the TRANSPORT. It cannot inspect
 * encrypted HTTPS content inside Telegram — app blocking + install control
 * are the primary bypass controls; DNS is secondary.
 */
class BypassDomainFilterTest {

    private fun blocklist(): Blocklist = Blocklist(
        listOf(
            "pornhub.com",
            "xvideos.com",
            "onlyfans.com",
            "terabox.com",
        ),
    )

    @Test
    fun `testE known adult domains remain blocked`() {
        val list = blocklist()
        assertTrue(list.contains("pornhub.com"))
        assertTrue(list.contains("www.pornhub.com"))
        assertTrue(list.contains("cdn.xvideos.com"))
        assertTrue(list.contains("onlyfans.com"))
    }

    @Test
    fun `adult heuristics still apply without false positives on telegram`() {
        val list = Blocklist(emptyList())
        // Heuristic substring brands still blocked.
        assertTrue(list.contains("freeporn.example.com"))
        // Telegram and legitimate hosts stay allowed.
        assertFalse(list.contains("telegram.org"))
        assertFalse(list.contains("t.me"))
        assertFalse(list.contains("www.google.com"))
        assertFalse(list.contains("sussex.ac.uk"))
        assertFalse(list.contains("adultswim.com"))
    }

    @Test
    fun `testF bypass cdn and encrypted-dns transports are identified`() {
        assertTrue(BlockedAppPolicy.isBypassDomain("terabox.com"))
        assertTrue(BlockedAppPolicy.isBypassDomain("api.terabox.com"))
        assertTrue(BlockedAppPolicy.isBypassDomain("dns.google"))
        assertTrue(BlockedAppPolicy.isBypassDomain("cloudflare-dns.com"))
    }

    @Test
    fun `testF telegram and legitimate browsing are not bypass-flagged`() {
        assertFalse(BlockedAppPolicy.isBypassDomain("telegram.org"))
        assertFalse(BlockedAppPolicy.isBypassDomain("t.me"))
        assertFalse(BlockedAppPolicy.isBypassDomain("www.google.com"))
        assertFalse(BlockedAppPolicy.isBypassDomain("m.youtube.com"))
    }
}
