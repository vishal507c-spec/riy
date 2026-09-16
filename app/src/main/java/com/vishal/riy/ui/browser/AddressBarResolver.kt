package com.vishal.riy.ui.browser

import java.net.URLEncoder
import java.util.Locale

/**
 * Turns raw address-bar input into something to load, with NO Android or UI
 * dependencies so the decision is unit-testable.
 *
 *  - "http(s)://example/path" -> loaded as-is
 *  - "example.com", "sub.example.co.uk" -> treated as a web address and
 *    prefixed with https://
 *  - everything else ("porn", "hot photo", "how to tie a tie") -> turned into
 *    a Google search URL. That search URL then flows through
 *    [com.vishal.riy.blocker.SearchKeywordPolicy] inside the WebView, so an
 *    adult query is blocked BEFORE its results page is fetched.
 *
 * Generic words are never blindly blocked here: "hot weather" becomes a
 * search that the keyword policy allows, while "porn" becomes a search the
 * policy blocks. The allow/block decision is always the policy's, never the
 * address bar's.
 */
object AddressBarResolver {

    /** The page the browser shows on Home / fresh start. */
    const val HOME_URL = "https://www.google.com/"

    private const val SEARCH_TEMPLATE = "https://www.google.com/search?q=%s"

    /** Maps user input to a URL ready for [android.webkit.WebView.loadUrl]. */
    fun toLoadTarget(input: String): String {
        val trimmed = input.trim()
        if (trimmed.isEmpty()) return HOME_URL
        val lower = trimmed.lowercase(Locale.US)
        if (lower.startsWith("http://") || lower.startsWith("https://")) return trimmed
        if (lower.startsWith("about:")) return trimmed
        if (looksLikeDomain(lower)) return "https://$trimmed"
        return SEARCH_TEMPLATE.format(URLEncoder.encode(trimmed, "UTF-8"))
    }

    /**
     * True when [input] should be treated as a web address rather than a
     * search: it has no spaces, at least two dot-separated labels, and a
     * final label that looks like a TLD (2..6 ASCII letters). This keeps
     * "github.com" direct while "hot coffee" and "porn" go to search.
     */
    fun looksLikeDomain(input: String): Boolean {
        val s = input.trim().lowercase(Locale.US)
        if (s.isEmpty() || s.any { it.isWhitespace() }) return false
        if ('.' !in s) return false
        val host = s.substringBefore('/').substringBefore('?')
        val labels = host.split('.')
        if (labels.size < 2) return false
        val tld = labels.last()
        return tld.length in 2..6 && tld.all { it.isLetter() }
    }

    /** Compact, scheme-free text for the address bar. */
    fun displayText(url: String): String {
        if (url.isBlank() || url == "about:blank") return ""
        return url.removePrefix("https://").removePrefix("http://").ifBlank { url }
    }
}
