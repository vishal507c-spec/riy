package com.vishal.riy.blocker

import java.net.URI
import java.net.URLDecoder

/**
 * Adult search-query policy for search-engine URLs (Google/Bing/DDG/Yandex/
 * Yahoo/Ecosia/Brave) rendered inside THIS app's WebView.
 *
 * Scope note (honest): search queries typed in CHROME are inside its TLS
 * traffic and never observable by any non-MITM Android app — this layer can
 * only act on search URLs loaded in the in-app WebView, where it blocks the
 * main-frame request BEFORE the results page is fetched.
 *
 * Matching rules (context-aware, false-positive safe):
 *  1. HARD_TOKENS: explicit adult words block on their own as WHOLE words
 *     ("porn", "xxx", "sex", "nude", ...) — "Essex"/"sexton"/"dickens" are
 *     different words and never match.
 *  2. COMBO rules: generic trigger words (hot/sexy/adult) block only when a
 *     target word (girl/photo/pics/images/woman/women/content) appears in the
 *     SAME query: "hot photo" blocks, "hot weather"/"adult education" allow.
 *
 * Decoding handled: '+' and %20 separators, %XX encoding (including double
 * encoding), any letter case.
 */
object SearchKeywordPolicy {

    enum class Decision { ALLOW, BLOCK_ADULT_QUERY }

    internal val SEARCH_HOSTS = setOf(
        "google.com", "bing.com", "duckduckgo.com", "yandex.com", "yandex.ru",
        "search.yahoo.com", "ecosia.org", "startpage.com", "brave.com",
        "qwant.com", "mojeek.com",
    )

    /** Explicit adult words: block when present as a whole query word. */
    internal val HARD_TOKENS = setOf(
        "porn", "porno", "xxx", "sex", "sexual", "sexytimes",
        "nude", "nudity", "naked", "blowjob", "hentai", "rule34", "nsfw",
        "erotic", "gangbang", "masturbation", "pussy", "dick", "penis",
        "vagina", "boobs", "tits", "horny", "escort", "camgirl",
        "xnxx", "xvideos", "xhamster", "redtube", "pornhub", "onlyfans",
        "spankbang", "brazzers", "milf", "threesome", "creampie", "deepthroat",
        "handjob", "cumshot", "bdsm", "fetish", "striptease", "camwhore",
    )

    /** Generic words that only block in COMBINATION with a target word. */
    internal val TRIGGER_WORDS = setOf("hot", "sexy", "adult")

    internal val TARGET_WORDS = setOf(
        "girl", "girls", "photo", "photos", "pic", "pics", "image",
        "images", "woman", "women", "content",
    )

    /** True when [url] is a search-results URL on a known search engine. */
    fun isSearchUrl(url: String): Boolean {
        val host = hostOf(url) ?: return false
        if (SEARCH_HOSTS.none { host == it || host.endsWith(".$it") }) return false
        return queryParamOf(url) != null || url.contains("/search")
    }

    /** Extracts, decodes and normalizes the search query, or null. */
    fun extractQuery(url: String): String? {
        val host = hostOf(url) ?: return null
        if (SEARCH_HOSTS.none { host == it || host.endsWith(".$it") }) return null
        val raw = queryParamOf(url) ?: return null
        return normalizeQuery(raw)
    }

    /** Policy decision for a search-engine URL: block adult queries. */
    fun evaluate(url: String): Decision {
        val query = extractQuery(url) ?: return Decision.ALLOW
        return if (isAdultQuery(query)) Decision.BLOCK_ADULT_QUERY else Decision.ALLOW
    }

    fun isAdultQuery(query: String): Boolean {
        val tokens = normalizeQuery(query).split(' ').filter { it.isNotBlank() }
        if (tokens.isEmpty()) return false
        if (tokens.any { it in HARD_TOKENS }) return true
        val hasTrigger = tokens.any { it in TRIGGER_WORDS }
        val hasTarget = tokens.any { it in TARGET_WORDS }
        return hasTrigger && hasTarget
    }

    /**
     * URL-decodes (twice, for double-encoded bypasses), lower-cases and
     * collapses every separator to single spaces.
     */
    fun normalizeQuery(raw: String): String {
        var s = decode(raw)
        if (s.contains('%')) s = decode(s)
        return s.lowercase().replace(Regex("[^a-z0-9]+"), " ").trim()
    }

    private fun decode(s: String): String =
        runCatching { URLDecoder.decode(s, "UTF-8") }.getOrDefault(s)

    private fun hostOf(url: String): String? = runCatching {
        URI(url).host?.lowercase()
    }.getOrNull()

    /** Raw (still-encoded) value of the q=/text=/p= query parameter. */
    private fun queryParamOf(url: String): String? {
        val raw = runCatching { URI(url).rawQuery }.getOrNull() ?: return null
        for (pair in raw.split('&')) {
            val i = pair.indexOf('=')
            if (i <= 0) continue
            val key = pair.substring(0, i).lowercase()
            if (key == "q" || key == "text" || key == "p") {
                return pair.substring(i + 1).ifEmpty { null }
            }
        }
        return null
    }
}
