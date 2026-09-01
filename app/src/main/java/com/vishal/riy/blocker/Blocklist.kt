package com.vishal.riy.blocker

/**
 * Pure-JVM adult-domain matcher used by the DNS filter.
 *
 * Rules are loaded from `assets/blocklist.txt` (one domain per line; a rule
 * matches itself and every subdomain) and combined with code-level
 * heuristics designed to keep false positives near zero:
 *
 *  - The dedicated adult TLDs (.xxx .adult .porn .sex .sexy) always block.
 *  - The substring keywords ("porn"/"hentai"/"xnxx"/"xvideos") are safe as
 *    plain substrings on the full host: there are no common innocent domains
 *    containing them.
 *  - Token keywords (sex/xxx/adult/nude/...) must match a WHOLE label of the
 *    registrable part, so "sussex.ac.uk", "adultswim.com", "xxxlutz.com" or
 *    "sexton.dev" are never blocked.
 */
class Blocklist(rules: List<String> = emptyList()) {

    private val exactDomains: Set<String> = rules
        .map { normalizeDomain(it) }
        .filter { it.contains('.') }
        .toSet()

    /** Number of loaded explicit domain rules (heuristics excluded). */
    val ruleCount: Int get() = exactDomains.size

    /** True when the DNS query for [domain] must be blocked. */
    fun contains(domain: String?): Boolean {
        if (domain.isNullOrBlank()) return false
        val host = normalizeDomain(domain)
        if (!host.contains('.')) return false
        if (host in exactDomains) return true
        // Walk up the parent labels: any.sub.xvideos.com -> xvideos.com.
        var current = host
        while (true) {
            val dot = current.indexOf('.')
            if (dot <= 0 || dot == current.length - 1) break
            current = current.substring(dot + 1)
            if (current in exactDomains) return true
        }
        return matchesByHeuristic(host)
    }

    private fun matchesByHeuristic(host: String): Boolean {
        val labels = host.split('.')
        if (labels.size < 2) return false
        if (labels.last() in ADULT_TLDS) return true

        // Substring keywords are safe on the FULL host: no innocent domain or
        // subdomain contains them (catches freeporn.example.com etc.).
        if (SUBSTRING_KEYWORDS.any { host.contains(it) }) return true

        // Token keywords must match a WHOLE label of the registrable part
        // (last two labels, or three for multi-part suffixes like co.uk) so
        // "sussex.ac.uk", "adultswim.com" or "xxxlutz.com" stay allowed.
        val skippable = { l: String -> l == "com" || l == "co" || l == "org" || l == "net" }
        val baseCount = if (labels.size >= 3 && skippable(labels[labels.size - 2])) 3 else 2
        return labels.takeLast(baseCount).any { it in TOKEN_LABELS }
    }

    companion object {
        internal val ADULT_TLDS = setOf("xxx", "adult", "porn", "sex", "sexy")
        // Substring keywords: no common innocent domain contains these.
        internal val SUBSTRING_KEYWORDS = listOf("porn", "hentai", "xnxx", "xvideos")
        internal val TOKEN_LABELS = setOf(
            "porn", "sex", "xxx", "adult", "hentai", "nude", "nudes",
        )

        fun normalizeDomain(domain: String): String =
            domain.trim()
                .lowercase()
                .removePrefix("https://")
                .removePrefix("http://")
                .removePrefix("www.")
                .removeSuffix(".")
                .substringBefore('/')

        /** Parses blocklist lines: strips comments/blanks, keeps domains only. */
        fun parseRules(rawLines: Sequence<String>): List<String> = rawLines
            .map { it.substringBefore('#').trim() }
            .filter { it.isNotEmpty() }
            .filter { !it.startsWith('.') }
            .distinct()
            .toList()
    }
}
