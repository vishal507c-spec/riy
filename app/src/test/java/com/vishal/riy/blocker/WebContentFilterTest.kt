package com.vishal.riy.blocker

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * URL-level content classification used by the in-app WebView filter:
 * adult hosts are blocked everywhere, explicit-URL assets are blocked inside
 * otherwise-normal pages, and fashion/news/education vocabulary must NEVER
 * trigger a block.
 */
class WebContentFilterTest {

    private val blocklist = Blocklist(listOf("pornhub.com", "xvideos.com", "chaturbate.com"))

    // ------------------------------------------------------------- host layer

    @Test
    fun `adult hosts are blocked`() {
        assertEquals(
            WebContentFilter.Decision.BLOCK_HOST,
            WebContentFilter.decide("www.pornhub.com", "https://www.pornhub.com/view.php", blocklist),
        )
        assertEquals(
            WebContentFilter.Decision.BLOCK_HOST,
            WebContentFilter.decide("cdn.xvideos.com", "https://cdn.xvideos.com/a.jpg", blocklist),
        )
    }

    // --------------------------------------------------------------- URL layer

    @Test
    fun `explicit asset URLs are blocked inside normal pages`() {
        assertEquals(
            WebContentFilter.Decision.BLOCK_URL,
            WebContentFilter.decide("example.com", "https://example.com/images/porn/xxx_01.jpg", blocklist),
        )
        assertEquals(
            WebContentFilter.Decision.BLOCK_URL,
            WebContentFilter.decide("example.com", "https://example.com/pics/nsfw/thumb.png", blocklist),
        )
        assertEquals(
            WebContentFilter.Decision.BLOCK_URL,
            WebContentFilter.decide("cdn.example.com", "https://cdn.example.com/sex/photo.jpg", blocklist),
        )
        assertEquals(
            WebContentFilter.Decision.BLOCK_URL,
            WebContentFilter.decide("example.com", "https://example.com/hentai/gallery.html", blocklist),
        )
    }

    @Test
    fun `fashion and normal vocabulary is never blocked`() {
        // "sexy dress" / "nude heels" are legitimate fashion terms.
        assertEquals(
            WebContentFilter.Decision.ALLOW,
            WebContentFilter.decide("fashion.com", "https://fashion.com/shop/sexy-dress.jpg", blocklist),
        )
        assertEquals(
            WebContentFilter.Decision.ALLOW,
            WebContentFilter.decide("fashion.com", "https://fashion.com/nude-heels.jpg", blocklist),
        )
        // "essex", "sussex", "middlesex" contain "sex" but with letters around.
        assertEquals(
            WebContentFilter.Decision.ALLOW,
            WebContentFilter.decide("visit-essex.com", "https://visit-essex.com/guide.html", blocklist),
        )
        assertEquals(
            WebContentFilter.Decision.ALLOW,
            WebContentFilter.decide("example.com", "https://example.com/roentgen_xxx_study.pdf", blocklist),
        )
        // Normal news / education / shopping images.
        assertEquals(
            WebContentFilter.Decision.ALLOW,
            WebContentFilter.decide("news.com", "https://news.com/img/match-highlight.jpg", blocklist),
        )
        assertEquals(
            WebContentFilter.Decision.ALLOW,
            WebContentFilter.decide("example.com", "https://example.com/biology-lesson.html", blocklist),
        )
    }

    @Test
    fun `token matching requires word boundaries`() {
        // "sexy" must NOT match the token "sex" (y is alphanumeric).
        assertFalse("sexy-dress.jpg".contains("sex") && WebContentFilter.decide(
            "f.com", "https://f.com/sexy-dress.jpg", blocklist,
        ) == WebContentFilter.Decision.BLOCK_URL)
        // But a bare /sex/ path segment does match.
        assertEquals(
            WebContentFilter.Decision.BLOCK_URL,
            WebContentFilter.decide("f.com", "https://f.com/sex/page.html", blocklist),
        )
    }

    @Test
    fun `null or blank input is allowed`() {
        assertEquals(WebContentFilter.Decision.ALLOW, WebContentFilter.decide(null, null, blocklist))
        assertEquals(WebContentFilter.Decision.ALLOW, WebContentFilter.decide("", null, blocklist))
        assertEquals(WebContentFilter.Decision.ALLOW, WebContentFilter.decide("example.com", null, blocklist))
    }
}
