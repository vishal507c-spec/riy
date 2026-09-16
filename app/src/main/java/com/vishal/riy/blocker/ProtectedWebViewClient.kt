package com.vishal.riy.blocker

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URL

/**
 * The protection pipeline for every resource loaded inside **riy's own
 * WebView** (the protected browser). Nothing here decrypts or inspects TLS:
 * the system gives an app no access to another browser's HTTPS traffic, and
 * MITM is deliberately not used. This client only sees the cleartext URLs the
 * WebView asks us to load — plus image bytes it fetches itself for on-device
 * classification.
 *
 * Layers, in order:
 *  1. **Adult search-query layer** ([SearchKeywordPolicy]) — a search-engine
 *     results request whose query is adult is answered locally with 403, so
 *     the results page is NEVER fetched. This is the layer that stops
 *     "google porn" / "hot photo" before a single result renders.
 *  2. **Host / explicit-URL layer** ([WebContentFilter]) — adult domains and
 *     explicit asset URLs are dropped.
 *  3. **Image layer** ([ImageClassifier]) — image bytes are classified
 *     on-device; explicit imagery (and the strict "highly suggestive" tier on
 *     image-search pages) is replaced by a blocked placeholder.
 *
 * The DNS / SafeSearch layers in [BlockerVpnService] run system-wide and are
 * additive: with protection ON, adult hosts never even resolve and Google
 * SafeSearch is enforced server-side. This client keeps riy's own browser
 * protected even when the VPN is not running.
 *
 * @param blockMainFrameHosts when true, adult hosts are also blocked at the
 * main frame (browser behaviour). The protection-TEST screen passes false so
 * it can honestly report whether the DNS layer was the thing that blocked.
 */
class ProtectedWebViewClient(
    private val blocklist: Blocklist,
    private val onEvent: (ProtectionEvent) -> Unit,
    private val blockMainFrameHosts: Boolean = true,
) : WebViewClient() {

    /**
     * Everything the UI may want to react to. [AdultSearchBlocked] /
     * [HostBlocked] / [AssetBlocked] are the protection decisions; the page
     * lifecycle events drive the address bar and the loading indicator.
     */
    sealed interface ProtectionEvent {
        data class PageStarted(val url: String) : ProtectionEvent
        data class PageFinished(val url: String) : ProtectionEvent
        data class PageError(val url: String, val description: String, val errorCode: Int) : ProtectionEvent
        data class AdultSearchBlocked(val url: String) : ProtectionEvent
        data class HostBlocked(val url: String) : ProtectionEvent
        data class AssetBlocked(val url: String) : ProtectionEvent
    }

    /**
     * True while the current page is an image-SEARCH result page (Google
     * Images etc.): there the user explicitly asked a search engine for
     * images, so [ImageClassifier]'s strict threshold applies.
     */
    @Volatile
    private var strictImageSearchPage = false

    override fun shouldInterceptRequest(
        view: WebView?,
        request: WebResourceRequest?,
    ): WebResourceResponse? {
        request ?: return null
        val url = request.url
        val urlStr = url.toString()

        if (request.isForMainFrame) {
            strictImageSearchPage = isImageSearchPage(url)
            // Layer 1 — adult search query: answer locally, results are never fetched.
            if (SearchKeywordPolicy.evaluate(urlStr) ==
                SearchKeywordPolicy.Decision.BLOCK_ADULT_QUERY
            ) {
                onEvent(ProtectionEvent.AdultSearchBlocked(urlStr))
                return adultSearchBlockedResponse()
            }
            // Layer 2 — adult host / explicit URL at the main frame.
            if (blockMainFrameHosts) {
                val decision = WebContentFilter.decide(url.host, urlStr, blocklist)
                if (decision != WebContentFilter.Decision.ALLOW) {
                    onEvent(ProtectionEvent.HostBlocked(urlStr))
                    return blockedResponse(mainFrame = true)
                }
            }
            return null
        }

        // Sub-resource layer 2 — adult host / explicit asset inside a page.
        val decision = WebContentFilter.decide(url.host, urlStr, blocklist)
        if (decision != WebContentFilter.Decision.ALLOW) {
            onEvent(ProtectionEvent.AssetBlocked(urlStr))
            return blockedResponse(mainFrame = false)
        }

        // Sub-resource layer 3 — classify image pixels on device.
        if (!isImageResource(urlStr)) return null
        val context = view?.context ?: return null
        ImageClassifier.ensureInit(context)
        val bytes = fetchImage(urlStr) ?: return null
        val nsfw = ImageClassifier.classify(urlStr, bytes, strictImageSearchPage)
        if (nsfw) {
            onEvent(ProtectionEvent.AssetBlocked(urlStr))
            return blockedResponse(mainFrame = false)
        }
        return WebResourceResponse(guessContentType(urlStr), null, bytes.inputStream())
    }

    override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
        request ?: return false
        val url = request.url
        val urlStr = url.toString()
        // Keep navigation inside the protected browser: never hand the user
        // to another app/scheme that riy cannot protect.
        val scheme = url.scheme?.lowercase()
        if (scheme != null && scheme != "http" && scheme != "https" && scheme != "about") {
            return true
        }
        if (SearchKeywordPolicy.evaluate(urlStr) ==
            SearchKeywordPolicy.Decision.BLOCK_ADULT_QUERY
        ) {
            onEvent(ProtectionEvent.AdultSearchBlocked(urlStr))
            return true
        }
        if (blockMainFrameHosts) {
            val decision = WebContentFilter.decide(url.host, urlStr, blocklist)
            if (decision != WebContentFilter.Decision.ALLOW) {
                onEvent(ProtectionEvent.HostBlocked(urlStr))
                return true
            }
        }
        return false
    }

    override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
        super.onPageStarted(view, url, favicon)
        url?.let { onEvent(ProtectionEvent.PageStarted(it)) }
    }

    override fun onPageFinished(view: WebView?, url: String?) {
        super.onPageFinished(view, url)
        url?.takeIf { it != "about:blank" }?.let { onEvent(ProtectionEvent.PageFinished(it)) }
    }

    override fun onReceivedError(
        view: WebView?,
        request: WebResourceRequest?,
        error: WebResourceError?,
    ) {
        super.onReceivedError(view, request, error)
        if (request == null || !request.isForMainFrame || error == null) return
        val description = error.description?.toString().orEmpty()
        val errorCode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) error.errorCode else -1
        onEvent(ProtectionEvent.PageError(request.url.toString(), description, errorCode))
    }

    // ------------------------------------------------------------- helpers

    private fun isImageSearchPage(url: Uri): Boolean {
        val host = url.host ?: return false
        val isGoogle = host == "www.google.com" || host.endsWith(".google.com")
        if (!isGoogle) return false
        val query = url.query ?: return false
        return query.contains("tbm=isch") || query.contains("udm=2")
    }

    private fun isImageResource(url: String): Boolean {
        val path = url.substringBefore('?').lowercase()
        if (path.endsWith(".jpg") || path.endsWith(".jpeg") || path.endsWith(".png") ||
            path.endsWith(".webp") || path.endsWith(".gif")
        ) {
            return true
        }
        // googleusercontent-style thumbnail hosts have no file extension.
        val host = Uri.parse(url).host ?: return false
        return host.contains("googleusercontent") || host.contains("gstatic")
    }

    private fun blockedResponse(mainFrame: Boolean): WebResourceResponse {
        val body = if (mainFrame) {
            "<html><body style='background:#1b1b1b;color:#fff;font-family:sans-serif;" +
                "display:flex;align-items:center;justify-content:center;height:100%'>" +
                "<h3>Content Blocked</h3></body></html>"
        } else {
            ""
        }
        return WebResourceResponse(
            "text/html", "utf-8", 403, "Blocked by Riy Protection",
            mapOf("Cache-Control" to "no-store"),
            body.byteInputStream(),
        )
    }

    /** "Adult Search Blocked" page served instead of the search results. */
    private fun adultSearchBlockedResponse(): WebResourceResponse {
        val body = "<html><body style='background:#1b1b1b;color:#fff;font-family:sans-serif;" +
            "display:flex;flex-direction:column;align-items:center;justify-content:center;height:100%'>" +
            "<h2>Adult Search Blocked</h2>" +
            "<p style='color:#aaa'>Protected browsing is enabled.</p>" +
            "</body></html>"
        return WebResourceResponse(
            "text/html", "utf-8", 403, "Blocked by Riy Protection",
            mapOf("Cache-Control" to "no-store"),
            body.byteInputStream(),
        )
    }

    /** Fetches image bytes for classification (bounded, on the WebView IO thread). */
    private fun fetchImage(url: String): ByteArray? {
        return try {
            val connection = URL(url).openConnection() as HttpURLConnection
            try {
                connection.connectTimeout = 6000
                connection.readTimeout = 6000
                connection.setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android) RiyFilter")
                if (connection.responseCode != 200) return null
                val input = connection.inputStream
                val out = ByteArrayOutputStream()
                val buffer = ByteArray(16 * 1024)
                var read: Int
                while (input.read(buffer).also { read = it } != -1) {
                    out.write(buffer, 0, read)
                    if (out.size() > ImageClassifier.MAX_BYTES) return null
                }
                out.toByteArray()
            } finally {
                connection.disconnect()
            }
        } catch (_: Exception) {
            null // fail-open: unreachable image stays visible rather than breaking the page
        }
    }

    private fun guessContentType(url: String): String {
        val path = url.substringBefore('?').lowercase()
        return when {
            path.endsWith(".png") -> "image/png"
            path.endsWith(".webp") -> "image/webp"
            path.endsWith(".gif") -> "image/gif"
            else -> "image/jpeg"
        }
    }

    companion object {
        private const val BLOCKLIST_ASSET = "blocklist.txt"

        /**
         * The real adult-domain blocklist (plus code-level heuristics — see
         * [Blocklist]). Failure to read the asset never breaks browsing: the
         * heuristics in [Blocklist] still apply.
         */
        fun loadBlocklist(context: Context): Blocklist = try {
            context.assets.open(BLOCKLIST_ASSET).bufferedReader().useLines {
                Blocklist(Blocklist.parseRules(it))
            }
        } catch (e: Exception) {
            Blocklist(emptyList())
        }
    }
}
