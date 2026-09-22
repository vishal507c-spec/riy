package com.vishal.riy.blocker

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BlocklistTest {

    private val blocklist = Blocklist(
        listOf(
            "pornhub.com", "xvideos.com", "xhamster.com", "chaturbate.com",
            "sex.com", "onlyfans.com", "example-adult-site.net",
        ),
    )

    // ------------------------------------------------------------ blocking

    @Test
    fun `blocks exact domain`() {
        assertTrue(blocklist.contains("pornhub.com"))
        assertTrue(blocklist.contains("xvideos.com"))
        assertTrue(blocklist.contains("onlyfans.com"))
    }

    @Test
    fun `blocks www and deep subdomains`() {
        assertTrue(blocklist.contains("www.pornhub.com"))
        assertTrue(blocklist.contains("cn.pornhub.com"))
        assertTrue(blocklist.contains("a.b.c.pornhub.com"))
        assertTrue(blocklist.contains("www2.chaturbate.com"))
    }

    @Test
    fun `blocks normalized variants`() {
        assertTrue(blocklist.contains("  WWW.PornHub.COM.  "))
        assertTrue(blocklist.contains("https://www.pornhub.com/video"))
        assertTrue(blocklist.contains("http://xvideos.com/watch"))
    }

    @Test
    fun `blocks via substring keyword heuristics`() {
        // Domains that contain the safe substring keywords anywhere.
        assertTrue(blocklist.contains("freeporn.example.com"))
        assertTrue(blocklist.contains("xnxx.cyou"))
        assertTrue(blocklist.contains("xvideos.io"))
        assertTrue(blocklist.contains("hentaihaven.xxx"))
        assertTrue(blocklist.contains("pornhub.org"))
    }

    @Test
    fun `blocks token label heuristics in registrable part`() {
        assertTrue(blocklist.contains("sex.com")) // explicit rule
        assertTrue(blocklist.contains("xxx.xxx"))
        assertTrue(blocklist.contains("xxx.site"))
        assertTrue(blocklist.contains("nudes.gallery"))
    }

    @Test
    fun `blocks dedicated adult TLDs`() {
        assertTrue(blocklist.contains("something.xxx"))
        assertTrue(blocklist.contains("anything.adult"))
        assertTrue(blocklist.contains("anything.porn"))
        assertTrue(blocklist.contains("anything.sex"))
        assertTrue(blocklist.contains("anything.sexy"))
    }

    @Test
    fun `blocks adult brand mirrors via brand substrings`() {
        // Mirror/CDN TLDs of adult brands that are not in the exact list.
        assertTrue(blocklist.contains("xhamster.one")) // exact rule, but also:
        assertTrue(blocklist.contains("xhamster.io"))
        assertTrue(blocklist.contains("redtube.io"))
        assertTrue(blocklist.contains("chaturbate.asia"))
        assertTrue(blocklist.contains("stripchat.net"))
        assertTrue(blocklist.contains("onlyfans.blog"))
        assertTrue(blocklist.contains("missav.ws"))
        assertTrue(blocklist.contains("rule34.video"))
        assertTrue(blocklist.contains("static.redgifs.net"))
    }

    @Test
    fun `blocks suggestive token labels`() {
        assertTrue(blocklist.contains("nsfw.com"))
        assertTrue(blocklist.contains("sexy.xxx"))
        assertTrue(blocklist.contains("boobs.gallery"))
        assertTrue(blocklist.contains("erotic.art"))
    }

    // ------------------------------------------------------ false positives

    @Test
    fun `allows normal websites`() {
        assertFalse(blocklist.contains("google.com"))
        assertFalse(blocklist.contains("github.com"))
        assertFalse(blocklist.contains("moma.org"))
        assertFalse(blocklist.contains("wikipedia.org"))
        assertFalse(blocklist.contains("stackoverflow.com"))
        assertFalse(blocklist.contains("example.com"))
    }

    @Test
    fun `allows domains containing keyword-like substrings inside words`() {
        // "sex" as a whole label only — never as a substring.
        assertFalse(blocklist.contains("sussex.ac.uk"))
        assertFalse(blocklist.contains("middlesex-county.gov"))
        assertFalse(blocklist.contains("sextonfamily.org"))
        // "adult"/"xxx" as whole labels only.
        assertFalse(blocklist.contains("adultswim.com"))
        assertFalse(blocklist.contains("xxxlutz.com"))
        assertFalse(blocklist.contains("adultingcourse.com"))
        // "porn"/"hentai" substring is safe, but tokens inside innocent words stay allowed.
        assertFalse(blocklist.contains("sculptural.art"))
    }

    @Test
    fun `allows innocent subdomains of innocent registrable domains`() {
        // Token keywords only apply to the registrable part, and "sex"/"adult"
        // are never substring keywords, so these stay allowed.
        assertFalse(blocklist.contains("adult-blog.wikipedia.org"))
        assertFalse(blocklist.contains("sex-history.github.io"))
        assertFalse(blocklist.contains("adult.example.net"))
    }

    @Test
    fun `new suggestive heuristics keep innocent lookalikes allowed`() {
        // "nsfw" is a whole-label token: the ML library nsfwjs.com stays up.
        assertFalse(blocklist.contains("nsfwjs.com"))
        // "escort" is a whole-label token: escortedtours.com (travel) stays up.
        assertFalse(blocklist.contains("escortedtours.com"))
        assertFalse(blocklist.contains("escort-agency-list.gov"))
        // "sexy" whole-label only: sexyhair.com (salon brand) stays up.
        assertFalse(blocklist.contains("sexyhair.com"))
        assertFalse(blocklist.contains("github.com"))
        assertFalse(blocklist.contains("stackoverflow.com"))
        assertFalse(blocklist.contains("wikipedia.org"))
    }

    @Test
    fun `handles null blank and malformed input`() {
        assertFalse(blocklist.contains(null))
        assertFalse(blocklist.contains(""))
        assertFalse(blocklist.contains("   "))
        assertFalse(blocklist.contains("localhost"))
        assertFalse(blocklist.contains("."))
    }

    // ---------------------------------------------------------- rule parsing

    @Test
    fun `parses rules with comments and blanks`() {
        val raw = listOf(
            "# comment",
            "",
            "  pornhub.com  ",
            ".dot-ignored.com",
            "xvideos.com",
            "xvideos.com",
            "inline-comment.com # keep domain, drop trailing comment",
        )
        val rules = Blocklist.parseRules(raw.asSequence())
        assertEquals(listOf("pornhub.com", "xvideos.com", "inline-comment.com"), rules)
    }

    @Test
    fun `empty blocklist still applies heuristics`() {
        val heuristicOnly = Blocklist(emptyList())
        assertTrue(heuristicOnly.contains("anything.porn"))
        assertTrue(heuristicOnly.contains("freeporn.com"))
        assertFalse(heuristicOnly.contains("github.com"))
    }

    @Test
    fun `ruleCount reports loaded rules`() {
        assertEquals(7, blocklist.ruleCount)
    }

    // ------------------------------------------- Phase 10 match classification

    @Test
    fun `an explicit rule, an adult TLD and a brand substring are all DEFINITIVE`() {
        assertEquals(BlocklistMatch.DEFINITIVE, blocklist.classify("pornhub.com"))
        assertEquals(BlocklistMatch.DEFINITIVE, blocklist.classify("anything.porn"))
        assertEquals(BlocklistMatch.DEFINITIVE, blocklist.classify("freeporn.com"))
        assertEquals(BlocklistMatch.DEFINITIVE, blocklist.classify("xvideos.mirror.io"))
    }

    @Test
    fun `a whole-label token of the registrable part is SUSPECT`() {
        // The ambiguous class: plausible, but not positively identified.
        assertEquals(BlocklistMatch.SUSPECT, blocklist.classify("nude.example"))
        assertEquals(BlocklistMatch.SUSPECT, blocklist.classify("adult.guru"))
        assertEquals(BlocklistMatch.SUSPECT, blocklist.classify("nsfw.la"))
    }

    @Test
    fun `classify is null for ordinary and medical domains`() {
        // The false-positive guarantees the blocklist already made, now exposed
        // through the classification the intelligence layer consumes.
        assertNull(blocklist.classify("google.com"))
        assertNull(blocklist.classify("youtube.com"))
        assertNull(blocklist.classify("sussex.ac.uk"))
        assertNull(blocklist.classify("adultswim.com"))
        assertNull(blocklist.classify("xxxlutz.com"))
        assertNull(blocklist.classify("mayoclinic.org"))
    }

    @Test
    fun `classify and contains never disagree about what is blocked`() {
        listOf(
            "pornhub.com", "anything.porn", "freeporn.com",
            "nude.example", "adult.guru", "google.com", "sussex.ac.uk",
        ).forEach { domain ->
            val classified = blocklist.classify(domain)
            assertEquals(
                "classify must answer contains for '$domain'",
                classified != null,
                blocklist.contains(domain),
            )
        }
    }

    @Test
    fun `malformed and blank input classifies to nothing`() {
        assertNull(blocklist.classify(null))
        assertNull(blocklist.classify(""))
        assertNull(blocklist.classify("localhost"))
        assertNull(blocklist.classify("   "))
    }
}
