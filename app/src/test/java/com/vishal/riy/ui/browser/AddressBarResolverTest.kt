package com.vishal.riy.ui.browser

import com.vishal.riy.blocker.SearchKeywordPolicy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Address-bar input handling. This is the layer that turns typing into a URL,
 * so it is where "the user typed PORN into riy's browser" is proven to become
 * a search URL that the keyword policy blocks BEFORE results load — and where
 * the required false positives ("hot weather") are proven to stay allowed.
 */
class AddressBarResolverTest {

    // ------------------------------------------------- typed text becomes a search

    @Test
    fun `adult queries become search urls that the policy blocks`() {
        for (typed in listOf(
            "porn", "xxx", "sex", "nude", "naked", "nsfw", "erotic",
            "hot photo", "sexy photo", "hot girl", "sexy girl", "nude photo",
            "porn images",
        )) {
            val target = AddressBarResolver.toLoadTarget(typed)
            assertTrue(
                "'$typed' must become a Google search URL (got $target)",
                target.startsWith("https://www.google.com/search?q="),
            )
            assertEquals(
                "'$typed' must be BLOCKED before its results page loads",
                SearchKeywordPolicy.Decision.BLOCK_ADULT_QUERY,
                SearchKeywordPolicy.evaluate(target),
            )
        }
    }

    @Test
    fun `benign typed text becomes a search the policy allows`() {
        for (typed in listOf(
            "hot weather", "hot coffee", "fashion photo", "adult education",
            "essex county", "wikipedia", "how to tie a tie",
        )) {
            val target = AddressBarResolver.toLoadTarget(typed)
            assertTrue(
                "'$typed' must become a Google search URL (got $target)",
                target.startsWith("https://www.google.com/search?q="),
            )
            assertEquals(
                "'$typed' must be ALLOWED",
                SearchKeywordPolicy.Decision.ALLOW,
                SearchKeywordPolicy.evaluate(target),
            )
        }
    }

    // --------------------------------------------------- typed domains load directly

    @Test
    fun `web addresses are loaded directly with https`() {
        assertEquals("https://github.com", AddressBarResolver.toLoadTarget("github.com"))
        assertEquals("https://www.wikipedia.org", AddressBarResolver.toLoadTarget("www.wikipedia.org"))
        assertEquals("https://example.co.uk/page", AddressBarResolver.toLoadTarget("example.co.uk/page"))
        assertEquals("https://www.google.com", AddressBarResolver.toLoadTarget("https://www.google.com"))
        assertEquals("http://example.com", AddressBarResolver.toLoadTarget("http://example.com"))
    }

    @Test
    fun `typed adult domains are still handled by the host layer not the search layer`() {
        // A typed adult domain is loaded directly (not searched). The keyword
        // policy cannot see a query here — this is exactly why the URL/host
        // layer exists, and why it must block such hosts.
        val target = AddressBarResolver.toLoadTarget("pornhub.com")
        assertEquals("https://pornhub.com", target)
        assertFalse(AddressBarResolver.looksLikeDomain("porn"))
        assertTrue(AddressBarResolver.looksLikeDomain("pornhub.com"))
    }

    @Test
    fun `sentences with dots are treated as searches not domains`() {
        // "hot.photo" has a dot but no plausible TLD-sized final label is the
        // point: the guard is "no spaces + alpha TLD". A sentence keeps spaces.
        assertFalse(AddressBarResolver.looksLikeDomain("hot coffee"))
        assertFalse(AddressBarResolver.looksLikeDomain("what is 2.5 kg"))
        assertFalse(AddressBarResolver.looksLikeDomain("porn"))
        assertFalse(AddressBarResolver.looksLikeDomain(""))
    }

    @Test
    fun `empty input falls back to home`() {
        assertEquals(AddressBarResolver.HOME_URL, AddressBarResolver.toLoadTarget(""))
        assertEquals(AddressBarResolver.HOME_URL, AddressBarResolver.toLoadTarget("   "))
    }

    @Test
    fun `display text strips the scheme`() {
        assertEquals("www.google.com", AddressBarResolver.displayText("https://www.google.com"))
        assertEquals("example.com/x", AddressBarResolver.displayText("http://example.com/x"))
        assertEquals("", AddressBarResolver.displayText("about:blank"))
    }
}
