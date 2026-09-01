package com.vishal.riy.blocker

/**
 * URL-level content classification for WebViews rendered INSIDE this app
 * (system-wide HTTPS payloads cannot be inspected without MITM, which is
 * deliberately not used).
 *
 * Decisions, in order:
 *  1. Host on the adult blocklist                -> BLOCK_HOST (page + assets)
 *  2. URL path contains a HARD explicit token    -> BLOCK_URL (adult image or
 *     asset embedded in an otherwise-normal page: /porn/xxx.jpg,
 *     /images/xxx_01.jpg, /nsfw/thumb.png, ...).
 *
 * HARD_URL_TOKENS are deliberately narrow to keep false positives minimal:
 * word-boundary matched and fashion-safe. "nude" and "sexy" are NOT hard
 * tokens because "nude heels" and "sexy dress" are legitimate fashion terms
 * (the user allow-list requires non-sexual fashion content to keep working).
 */
object WebContentFilter {

    enum class Decision { ALLOW, BLOCK_HOST, BLOCK_URL }

    // Word-boundary matched against the URL path/query. All are explicit
    // adult markers; none appears inside common fashion/news/education words.
    internal val HARD_URL_TOKENS = listOf("porn", "xxx", "nsfw", "hentai", "sex")

    fun decide(host: String?, url: String?, blocklist: Blocklist): Decision {
        if (host.isNullOrBlank()) return Decision.ALLOW
        if (blocklist.contains(host)) return Decision.BLOCK_HOST
        val path = url?.lowercase() ?: return Decision.ALLOW
        if (HARD_URL_TOKENS.any { path.containsToken(it) }) return Decision.BLOCK_URL
        return Decision.ALLOW
    }

    /** True when [token] occurs with non-word characters around it. */
    private fun String.containsToken(token: String): Boolean {
        var idx = indexOf(token)
        while (idx >= 0) {
            val before = if (idx == 0) '/' else this[idx - 1]
            val afterIdx = idx + token.length
            val after = if (afterIdx >= length) '/' else this[afterIdx]
            // '_' counts as a word character: roentgen_xxx_study.pdf is not
            // an adult file, while /porn/xxx_01.jpg is caught by /porn/.
            val boundary = { c: Char -> !c.isLetterOrDigit() && c != '_' }
            if (boundary(before) && boundary(after)) return true
            idx = indexOf(token, idx + 1)
        }
        return false
    }
}
