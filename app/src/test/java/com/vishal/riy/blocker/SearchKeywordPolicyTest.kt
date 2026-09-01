package com.vishal.riy.blocker

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Adult search-keyword policy tests. Every example from the requirement is
 * covered, including URL-encoding / case / separator bypass attempts and the
 * explicit false-positive allow-list.
 */
class SearchKeywordPolicyTest {

    // ------------------------------------------------------------- BLOCK list

    @Test
    fun `hard explicit keywords block`() {
        for (q in listOf(
            "porn", "xxx", "sex", "nude", "naked", "blowjob", "hentai",
            "rule34", "nsfw", "erotic", "gangbang", "masturbation", "pussy",
            "penis", "vagina", "boobs", "tits", "horny", "camgirl", "sexual",
        )) {
            assertTrue("'$q' must block", SearchKeywordPolicy.isAdultQuery(q))
        }
    }

    @Test
    fun `required combos block`() {
        for (q in listOf(
            "hot photo", "sexy photo", "hot girl", "sexy girl", "nude girl",
            "nude photo", "naked girl", "adult images", "sex images",
            "porn images", "xxx images", "adult content", "hot pics",
            "sexy pics", "hot women",
        )) {
            assertTrue("'$q' must block", SearchKeywordPolicy.isAdultQuery(q))
        }
    }

    // ------------------------------------------------------------ ALLOW list

    @Test
    fun `required false-positive cases allow`() {
        for (q in listOf(
            "hot weather", "hot coffee", "adult education", "girl education",
            "fashion photo", "hot", "sexy", "girl", "photo", "adult",
            "sexyhair", "weather today", "coffee recipe", "leadership",
        )) {
            assertFalse("'$q' must allow", SearchKeywordPolicy.isAdultQuery(q))
        }
    }

    @Test
    fun `lookalike words never block`() {
        assertFalse(SearchKeywordPolicy.isAdultQuery("essex county"))
        assertFalse(SearchKeywordPolicy.isAdultQuery("sexton family"))
        assertFalse(SearchKeywordPolicy.isAdultQuery("dickens novel"))
        assertFalse(SearchKeywordPolicy.isAdultQuery("analysis report"))
        assertFalse(SearchKeywordPolicy.isAdultQuery("sussex travel"))
    }

    // ------------------------------------------------------- URL-level checks

    @Test
    fun `search urls detected across engines`() {
        assertTrue(SearchKeywordPolicy.isSearchUrl("https://www.google.com/search?q=test"))
        assertTrue(SearchKeywordPolicy.isSearchUrl("https://www.bing.com/search?q=test"))
        assertTrue(SearchKeywordPolicy.isSearchUrl("https://duckduckgo.com/?q=test"))
        assertTrue(SearchKeywordPolicy.isSearchUrl("https://yandex.com/search/?text=test"))
        assertFalse(SearchKeywordPolicy.isSearchUrl("https://www.wikipedia.org/wiki/Sex"))
        assertFalse(SearchKeywordPolicy.isSearchUrl("https://example.com/page"))
    }

    @Test
    fun `google images and web searches both evaluated`() {
        assertEquals(
            SearchKeywordPolicy.Decision.BLOCK_ADULT_QUERY,
            SearchKeywordPolicy.evaluate("https://www.google.com/search?q=hot+girl&tbm=isch"),
        )
        assertEquals(
            SearchKeywordPolicy.Decision.BLOCK_ADULT_QUERY,
            SearchKeywordPolicy.evaluate("https://www.google.com/search?q=porn"),
        )
    }

    @Test
    fun `query extraction handles engines`() {
        assertEquals(
            "hot photo",
            SearchKeywordPolicy.extractQuery("https://www.google.com/search?q=hot+photo&tbm=isch"),
        )
        assertEquals(
            "sexy photo",
            SearchKeywordPolicy.extractQuery("https://www.bing.com/search?q=sexy%20photo"),
        )
        assertEquals(
            "sexy girl",
            SearchKeywordPolicy.extractQuery("https://duckduckgo.com/?q=sexy%2Bgirl"),
        )
        assertEquals(
            "hot girl",
            SearchKeywordPolicy.extractQuery("https://yandex.com/search/?text=HOT%20girl"),
        )
        assertNull(SearchKeywordPolicy.extractQuery("https://www.google.com/search?tbm=isch"))
        assertNull(SearchKeywordPolicy.extractQuery("https://www.wikipedia.org/wiki/Sex?q=x"))
    }

    @Test
    fun `url encoded uppercase and separator bypasses are normalized`() {
        // %20 space
        assertEquals(
            SearchKeywordPolicy.Decision.BLOCK_ADULT_QUERY,
            SearchKeywordPolicy.evaluate("https://www.google.com/search?q=hot%20photo"),
        )
        // double-encoded %2520
        assertEquals(
            SearchKeywordPolicy.Decision.BLOCK_ADULT_QUERY,
            SearchKeywordPolicy.evaluate("https://www.google.com/search?q=hot%2520photo"),
        )
        // uppercase
        assertEquals(
            SearchKeywordPolicy.Decision.BLOCK_ADULT_QUERY,
            SearchKeywordPolicy.evaluate("https://www.google.com/search?q=HOT+GIRL"),
        )
        // extra separators between words
        assertEquals(
            SearchKeywordPolicy.Decision.BLOCK_ADULT_QUERY,
            SearchKeywordPolicy.evaluate("https://www.google.com/search?q=hot...girl"),
        )
        // '+' already decoded to space
        assertEquals(
            SearchKeywordPolicy.Decision.BLOCK_ADULT_QUERY,
            SearchKeywordPolicy.evaluate("https://www.google.com/search?q=sexy+girl+photos"),
        )
    }

    @Test
    fun `non-search urls are always allowed by this layer`() {
        assertEquals(
            SearchKeywordPolicy.Decision.ALLOW,
            SearchKeywordPolicy.evaluate("https://www.pornhub.com/"), // DNS layer's job
        )
        assertEquals(
            SearchKeywordPolicy.Decision.ALLOW,
            SearchKeywordPolicy.evaluate("https://www.google.com/maps?q=restaurant"),
        )
    }
}
