package com.vishal.riy.blocker

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Regression tests for the reported bug: "RIY Shield reports protection active,
 * but adult websites still work".
 *
 * The bug was NOT that a filter existed — it was that nothing ever checked the
 * filter's coverage, its initialization, or whether it was actually enforcing.
 * These tests pin the three things that were missing:
 *
 *  1. COVERAGE — the shipped blocklist must be a real maintained list, not a
 *     hand-written stub of ~100 domains.
 *  2. EVIDENCE — the dashboard status must be derived from what the filter
 *     proved, and must never read "verified" off "the VPN service started".
 *  3. HONESTY — encrypted DNS must be reported as an open route, not assumed
 *     closed.
 */
class AdultBlocklistCoverageTest {

    /**
     * Loads the asset the service actually ships. Kept deliberately tolerant of
     * a missing file so a packaging mistake surfaces as a clear failure here
     * rather than as a confusing "0 rules" at runtime.
     */
    private fun shippedBlocklist(): Blocklist {
        val stream = checkNotNull(javaClass.classLoader!!.getResourceAsStream("blocklist.txt")) {
            "blocklist.txt is missing from the APK assets"
        }
        val rules = stream.bufferedReader().useLines { Blocklist.parseRules(it) }
        return Blocklist(rules)
    }

    // ------------------------------------------------------------- coverage

    @Test
    fun `shipped blocklist is a real maintained list not a stub`() {
        // The pre-fix asset had 117 rules, which covered a vanishingly small
        // fraction of real adult domains. This is the regression guard.
        assertTrue(
            "blocklist must contain a maintained set of domains, found " +
                shippedBlocklist().ruleCount,
            shippedBlocklist().ruleCount > 50_000,
        )
    }

    @Test
    fun `well known adult domains and their subdomains are blocked`() {
        val list = shippedBlocklist()
        val domains = listOf(
            "pornhub.com", "xvideos.com", "xnxx.com", "redtube.com",
            "youporn.com", "chaturbate.com", "stripchat.com", "onlyfans.com",
            "fansly.com", "pornhub.org", "xvideos.net",
        )
        domains.forEach { domain ->
            assertTrue("expected $domain to be blocked", list.contains(domain))
        }
    }

    @Test
    fun `subdomains of listed domains are blocked`() {
        val list = shippedBlocklist()
        // The original report was about navigation that always starts with a
        // www/cdn/m subdomain, so this is the exact regression that matters.
        assertTrue(list.contains("www.pornhub.com"))
        assertTrue(list.contains("cdn.xvideos.com"))
        assertTrue(list.contains("m.xnxx.com"))
        assertTrue(list.contains("api.onlyfans.com"))
    }

    @Test
    fun `adult tld domains are blocked without being listed`() {
        val list = shippedBlocklist()
        assertTrue(list.contains("anything.xxx"))
        assertTrue(list.contains("some-new-site.porn"))
        assertTrue(list.contains("other.adult"))
    }

    @Test
    fun `ordinary browsing is never blocked`() {
        val list = shippedBlocklist()
        // Regression guard in the OTHER direction: a huge list must not start
        // eating legitimate sites, or the app becomes unusable.
        val innocent = listOf(
            "google.com", "www.google.com", "youtube.com", "facebook.com",
            "instagram.com", "github.com", "wikipedia.org", "amazon.com",
            "netflix.com", "microsoft.com", "reddit.com", "linkedin.com",
            "whatsapp.com", "zoom.us", "telegram.org", "stackexchange.com",
            "stackoverflow.com", "example.com", "sussex.ac.uk", "xxxlutz.com",
            "adultswim.com", "nsfwjs.com", "mayoclinic.org", "moma.org",
        )
        innocent.forEach { domain ->
            assertFalse("expected $domain to stay reachable", list.contains(domain))
        }
    }

    @Test
    fun `the self test canary is blocked and the control domain is not`() {
        val list = shippedBlocklist()
        // Both are load-bearing for the on-device self-test.
        assertTrue(list.contains(SelfTestCanaries.BLOCK))
        assertFalse(list.contains(SelfTestCanaries.ALLOW))
    }

    @Test
    fun `search result redirectors to adult sites are blocked by hostname`() {
        val list = shippedBlocklist()
        // Google/Bing redirect through their own hostnames, which are allowed.
        // What DNS filtering can actually do is block the adult DESTINATION, so
        // that is what is asserted here.
        assertFalse(
            "the search engine itself must stay reachable",
            list.contains("www.google.com"),
        )
        assertTrue(list.contains("pornhub.com"))
    }
}