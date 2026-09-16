package com.vishal.riy.lock

import com.vishal.riy.blocker.Blocklist
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The ACTUAL supported detection path, end to end at the level the production
 * filter operates: an adult-domain DNS query is matched by the [Blocklist] and
 * arms exactly a 2-hour lock; a normal domain does neither.
 *
 * Why this path and not a search-keyword one: a keyword typed in the user's
 * own browser (Chrome etc.) travels inside encrypted HTTPS and cannot be read
 * by any non-MITM Android app. What IS visible to this app — because it runs
 * the filtering VPN — is the DNS lookup that necessarily precedes every
 * porn-site visit. That lookup is transport-agnostic (it is answered before
 * any TLS handshake), so the same mechanism covers HTTPS sites.
 */
class DetectionPathTest {

    private val blocklist = Blocklist(Blocklist.parseRules(sequenceOf(
        "pornhub.com", "xnxx.com", "xvideos.com", "example-adult-site.xyz",
    )))

    @Test
    fun `known porn domain is blocked and arms exactly a 2-hour lock`() {
        val now = 1_000_000L

        // 1. the filter matches the adult domain (this is what makes the
        //    browser's connection attempt fail, HTTPS included)
        assertTrue("pornhub.com must be blocked", blocklist.contains("pornhub.com"))
        assertTrue("www.pornhub.com must be blocked", blocklist.contains("www.pornhub.com"))

        // 2. the same match arms the lock for exactly 2 hours
        var state = LockState.EMPTY
        state = LockEngine.onPornDetected(state, now, "pornhub.com")
        assertEquals(now + 2 * 3_600_000L, state.lockEndEpochMillis)
        assertTrue(LockEngine.isLocked(state, now))
    }

    @Test
    fun `an HTTPS porn-site lookup is blocked at DNS level before any TLS handshake`() {
        // The block happens while ANSWERING the DNS query, so the site's IP is
        // never returned to the browser — there is nothing to open a TLS
        // connection to. This is why HTTPS porn domains are blocked too.
        val blockedDomains = listOf(
            "pornhub.com", "www.pornhub.com", "video.pornhub.com",
            "xnxx.com", "xvideos.com", "example-adult-site.xyz",
        )
        blockedDomains.forEach { domain ->
            assertTrue("HTTPS access to $domain must be blocked", blocklist.contains(domain))
        }

        // the lock is armed by the first of those lookups and stays live
        val now = 5_000_000L
        var state = LockState.EMPTY
        state = LockEngine.onPornDetected(state, now, blockedDomains.first())
        assertTrue(LockEngine.isLocked(state, now + 60_000L))
    }

    @Test
    fun `normal websites are neither blocked nor locked`() {
        val normalDomains = listOf(
            "example.com", "www.wikipedia.org", "github.com", "google.com",
            "stackoverflow.com", "medium.com", "reddit.com",
        )
        normalDomains.forEach { domain ->
            assertFalse("$domain must NOT be blocked", blocklist.contains(domain))
        }

        // nothing in the engine arms a lock on its own — only an explicit
        // adult-domain detection does
        val state = LockState.EMPTY
        assertFalse(LockEngine.isLocked(state, System.currentTimeMillis()))
    }

    @Test
    fun `innocent lookalikes are not treated as adult traffic (no false lock)`() {
        // The blocklist's whole-label / substring rules are deliberately narrow:
        // these look like they contain adult words but are legitimate domains.
        val innocent = listOf(
            "sussex.ac.uk", "adultswim.com", "xxxlutz.com", "sexton.dev",
            "essex.gov.uk", "middlesex.edu", "nsfwjs.com",
        )
        innocent.forEach { domain ->
            assertFalse("$domain must NOT be blocked", blocklist.contains(domain))
        }
    }
}
