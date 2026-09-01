package com.vishal.riy.blocker

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.io.ByteArrayOutputStream
import java.util.concurrent.TimeUnit

/**
 * Network-level SafeSearch / Restricted-mode enforcement WITHOUT any MITM:
 *
 * The documented method (Google/Bing/DDG/Yandex network guides, also used by
 * AdGuard/CleanBrowsing DNS) maps a search engine's hostname to that provider
 * own dedicated "safe" frontend VIP. The frontend at that IP serves the SAME
 * site with a VALID certificate for the original hostname and forces safe
 * results SERVER-SIDE — the user cannot turn SafeSearch off from Google
 * settings, incognito mode, or another browser, because the enforcement
 * happens before the page is ever served.
 *
 *   google.com* -> forcesafesearch.google.com   (Google SafeSearch VIP)
 *   bing.com*   -> strict.bing.com
 *   duckduckgo  -> safe.duckduckgo.com
 *   yandex*     -> familysearch.yandex.ru
 *   youtube.com -> restrict.youtube.com          (YouTube strict mode)
 *
 * Google's SafeSearch VIP (216.239.38.120) is used as a hard-coded fallback
 * until the dynamic resolution refresh succeeds.
 */
object SafeSearchRules {

    internal val GOOGLE_SEARCH_HOSTS = setOf(
        "google.com", "www.google.com", "m.google.com", "encrypted.google.com",
        "www.google.co.in", "www.google.co.uk", "www.google.ca", "www.google.com.au",
        "www.google.com.br", "www.google.de", "www.google.fr", "www.google.es",
        "www.google.it", "www.google.nl", "www.google.com.mx", "www.google.co.jp",
        "www.google.co.id", "www.google.com.tr", "www.google.pl", "www.google.se",
    )
    internal val BING_HOSTS = setOf("bing.com", "www.bing.com", "cn.bing.com")
    internal val DDG_HOSTS = setOf("duckduckgo.com", "www.duckduckgo.com")
    internal val YANDEX_HOSTS = setOf("yandex.ru", "www.yandex.ru", "ya.ru")
    internal val YOUTUBE_HOSTS = setOf(
        "youtube.com", "www.youtube.com", "m.youtube.com",
        "youtube-nocookie.com", "www.youtube-nocookie.com",
    )

    /** Every "safe" frontend whose VIPs must be kept cached. */
    internal val SAFE_HOSTS = setOf(
        "forcesafesearch.google.com", "strict.bing.com",
        "safe.duckduckgo.com", "familysearch.yandex.ru", "restrict.youtube.com",
    )

    /** Google's documented SafeSearch VIP — fallback until resolution succeeds. */
    internal val GOOGLE_FALLBACK_V4 = byteArrayOf(216.toByte(), 239.toByte(), 38, 120)

    /** The provider "safe" hostname for [name], or null when the host is unmapped. */
    fun safeHostFor(name: String): String? = when (name) {
        in GOOGLE_SEARCH_HOSTS -> "forcesafesearch.google.com"
        in BING_HOSTS -> "strict.bing.com"
        in DDG_HOSTS -> "safe.duckduckgo.com"
        in YANDEX_HOSTS -> "familysearch.yandex.ru"
        in YOUTUBE_HOSTS -> "restrict.youtube.com"
        else -> null
    }

    /** Builds a recursive A/AAAA DNS query for [name] (sent to a real upstream). */
    fun buildDnsQuery(name: String, type: Int, id: Int = 0x2A2A): ByteArray {
        val out = ByteArrayOutputStream()
        fun w16(v: Int) {
            out.write((v shr 8) and 0xFF)
            out.write(v and 0xFF)
        }
        w16(id); w16(0x0100); w16(1); w16(0); w16(0); w16(0)
        name.split('.').forEach { label ->
            out.write(label.length)
            out.write(label.toByteArray(Charsets.US_ASCII))
        }
        out.write(0)
        w16(type); w16(1) // QTYPE, QCLASS IN
        return out.toByteArray()
    }
}

/**
 * Caches the "safe" VIPs and answers pinned A/AAAA for mapped hosts.
 *
 * Non-A/AAAA mapped queries (HTTPS RR type 65, TXT, ...) are answered NODATA:
 * forwarding Google's real HTTPS RR would leak address hints / ECH config that
 * let Chromium connect to the ORIGINAL IPs and bypass the pinned VIP.
 */
class SafeSearchEnforcer(
    private val scope: () -> CoroutineScope,
    private val resolve: suspend (name: String, type: Int) -> ByteArray?,
) {

    private val lock = Any()
    private val vips = HashMap<String, Pair<ByteArray?, ByteArray?>>()
    @Volatile private var lastRefreshMs = 0L

    /** Pinned DNS answer for a mapped host, or null when the query must be forwarded. */
    internal fun answerFor(query: ByteArray, q: DnsProtocol.DnsQuestion): ByteArray? {
        val safeHost = SafeSearchRules.safeHostFor(q.name) ?: return null
        val (v4, v6) = currentVips(safeHost)
        return when (q.type) {
            TYPE_A -> v4?.let { DnsProtocol.buildAddressResponse(query, q, it) }
            TYPE_AAAA -> v6?.let { DnsProtocol.buildAddressResponse(query, q, it) }
                ?: DnsProtocol.buildNoDataResponse(query, q)
            else -> DnsProtocol.buildNoDataResponse(query, q)
        }
    }

    private fun currentVips(safeHost: String): Pair<ByteArray?, ByteArray?> {
        maybeRefresh()
        synchronized(lock) {
            vips[safeHost]?.let { return it }
        }
        return if (safeHost == "forcesafesearch.google.com") {
            Pair(SafeSearchRules.GOOGLE_FALLBACK_V4, null)
        } else {
            Pair(null, null)
        }
    }

    private fun maybeRefresh() {
        val now = System.currentTimeMillis()
        if (now - lastRefreshMs < REFRESH_INTERVAL_MS) return
        lastRefreshMs = now // set immediately: at most one refresh per interval
        scope().launch(Dispatchers.IO) {
            runCatching { refreshAll() }
        }
    }

    /** Resolves every safe frontend's A/AAAA through the upstream resolver. */
    internal suspend fun refreshAll() {
        val next = HashMap<String, Pair<ByteArray?, ByteArray?>>()
        for (host in SafeSearchRules.SAFE_HOSTS) {
            // The upstream reply is a full DNS message (often with a CNAME
            // chain) — extract the actual A/AAAA rdata before caching it.
            val v4 = resolve(host, TYPE_A)?.let { DnsProtocol.firstAddressRdata(it, TYPE_A) }
            val v6 = resolve(host, TYPE_AAAA)?.let { DnsProtocol.firstAddressRdata(it, TYPE_AAAA) }
            if (v4 != null || v6 != null) next[host] = Pair(v4, v6)
        }
        if (next.isEmpty()) return
        synchronized(lock) { vips.putAll(next) } // stale entries survive failed refreshes
    }

    private companion object {
        private const val TYPE_A = 1
        private const val TYPE_AAAA = 28
        private val REFRESH_INTERVAL_MS = TimeUnit.HOURS.toMillis(6)
    }
}
