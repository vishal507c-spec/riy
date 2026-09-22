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
    fun contains(domain: String?): Boolean = classify(domain) != null

    /**
     * Phase 10 — classifies HOW [domain] matched, without changing whether it
     * did. [contains] is now a thin wrapper over this, so the two can never
     * disagree about what is blocked.
     *
     * The match class is the intelligence layer's only input for its
     * false-positive tiering (see [BlocklistMatch]): a
     * [DEFINITIVE][BlocklistMatch.DEFINITIVE] match is evidence on its own; a
     * [SUSPECT][BlocklistMatch.SUSPECT] match is an observation that needs
     * independent corroboration before it may become a protection event.
     */
    fun classify(domain: String?): BlocklistMatch? {
        if (domain.isNullOrBlank()) return null
        val host = normalizeDomain(domain)
        if (!host.contains('.')) return null
        if (host in exactDomains) return BlocklistMatch.DEFINITIVE
        // Walk up the parent labels: any.sub.xvideos.com -> xvideos.com.
        var current = host
        while (true) {
            val dot = current.indexOf('.')
            if (dot <= 0 || dot == current.length - 1) break
            current = current.substring(dot + 1)
            if (current in exactDomains) return BlocklistMatch.DEFINITIVE
        }
        return classifyByHeuristic(host)
    }

    /**
     * The heuristic arm, split by strength. Every rule below is one the matcher
     * already applied; this only reports WHICH one fired.
     */
    private fun classifyByHeuristic(host: String): BlocklistMatch? {
        val labels = host.split('.')
        if (labels.size < 2) return null
        if (labels.last() in ADULT_TLDS) return BlocklistMatch.DEFINITIVE

        // Substring keywords are safe on the FULL host: no innocent domain or
        // subdomain contains them (catches freeporn.example.com etc.).
        if (SUBSTRING_KEYWORDS.any { host.contains(it) }) return BlocklistMatch.DEFINITIVE

        // Token keywords must match a WHOLE label of the registrable part
        // (last two labels, or three for multi-part suffixes like co.uk) so
        // "sussex.ac.uk", "adultswim.com" or "xxxlutz.com" stay allowed.
        // This is the ONLY ambiguous class, so it is reported as SUSPECT.
        val skippable = { l: String -> l == "com" || l == "co" || l == "org" || l == "net" }
        val baseCount = if (labels.size >= 3 && skippable(labels[labels.size - 2])) 3 else 2
        return if (labels.takeLast(baseCount).any { it in TOKEN_LABELS }) BlocklistMatch.SUSPECT
        else null
    }

    companion object {
        internal val ADULT_TLDS = setOf("xxx", "adult", "porn", "sex", "sexy")
        // Substring keywords on the FULL host. Every entry is an adult-only
        // brand name — no common innocent domain contains any of them, so
        // mirrors like xvideos.io / chaturbate.asia are caught automatically.
        internal val SUBSTRING_KEYWORDS = listOf(
            "porn", "hentai", "xnxx", "xvideos", "xhamster", "redtube",
            "chaturbate", "stripchat", "bongacams", "camsoda", "livejasmin",
            "myfreecams", "spankbang", "spankwire", "onlyfans", "fansly",
            "imagefap", "motherless", "eporner", "tnaflix", "brazzers",
            "bangbros", "realitykings", "naughtyamerica", "teamskeet",
            "digitalplayground", "missav", "jable", "rule34", "redgifs",
            "erome", "fapello", "hitomi", "gelbooru",
        )
        // WHOLE-LABEL tokens of the registrable part: nsfw.example.com is
        // blocked while nsfwjs.com (ML library) stays allowed.
        internal val TOKEN_LABELS = setOf(
            "porn", "sex", "xxx", "adult", "hentai", "nude", "nudes",
            "nsfw", "sexy", "boobs", "erotic", "escort", "horny",
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
