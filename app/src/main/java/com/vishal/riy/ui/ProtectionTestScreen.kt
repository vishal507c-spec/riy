package com.vishal.riy.ui

import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import android.view.ViewGroup
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import com.vishal.riy.blocker.ImageClassifier
import com.vishal.riy.blocker.WebContentFilter
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme.colorScheme
import androidx.compose.material3.MaterialTheme.typography
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.zIndex
import com.vishal.riy.R
import com.vishal.riy.blocker.BlockerState
import com.vishal.riy.blocker.Blocklist

private val TEST_SITES = listOf(
    "https://www.google.com/search?q=porn" to "Google explicit search (SafeSearch check)",
    "https://www.google.com/search?q=porn&tbm=isch" to "Google Images explicit (SafeSearch check)",
    "https://www.google.com/search?q=hot+girl&tbm=isch" to "Google Images 'hot girl' (classifier check)",
    "https://thechive.com/" to "Suggestive photos site (classifier check)",
    "https://commons.wikimedia.org/wiki/Category:Nudity" to "Explicit nudity check (Wikimedia education)",
    "https://www.pornhub.com/" to "Pornhub (adult site)",
    "https://www.xvideos.com/" to "XVideos (adult site)",
    "https://www.wikipedia.org/" to "Wikipedia (normal site)",
)

/** DNS-level failure signatures produced by the filter (0.0.0.0/NXDOMAIN). */
private val DNS_BLOCK_ERRORS = listOf(
    "ERR_NAME_NOT_RESOLVED",
    "ERR_DNS_TIMED_OUT",
    "ERR_CONNECTION_REFUSED",
    "ERR_ADDRESS_UNREACHABLE",
    "ERR_NAME_RESOLUTION_FAILED",
)

private sealed interface TestResult {
    data object Running : TestResult
    data class Blocked(val url: String, val detail: String) : TestResult
    data class Loaded(val url: String) : TestResult
    data class Failed(val url: String, val detail: String) : TestResult
}

/**
 * "Check/Test Protection" screen. Loads a known adult site and a known normal
 * site in an in-app WebView:
 *  - DNS filtering active  -> the adult site never resolves and the app shows
 *    its own clean "Content Blocked" screen.
 *  - Content still loads   -> reported HONESTLY as not blocked.
 */
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun ProtectionTestScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    var target by remember { mutableStateOf<String?>(null) }
    var result by remember { mutableStateOf<TestResult?>(null) }
    var blockedAssets by remember { mutableIntStateOf(0) }

    val webView = remember {
        WebView(context).apply {
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
            )
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            webViewClient = TestWebViewClient(
                onResult = { testResult ->
                    // First definitive result wins; later duplicates are ignored.
                    if (result is TestResult.Running || result == null) result = testResult
                },
                onAssetBlocked = { blockedAssets++ },
            )
        }
    }
    DisposableEffect(Unit) {
        onDispose { webView.destroy() }
    }

    when {
        target == null -> SitePicker(
            onPick = { url ->
                result = TestResult.Running
                blockedAssets = 0
                target = url
            },
            onBack = onBack,
        )

        else -> TestRunView(
            webView = webView,
            target = target ?: "",
            result = result,
            blockedAssets = blockedAssets,
            onLoad = { url -> webView.loadUrl(url) },
            onBackToPicker = {
                webView.stopLoading()
                webView.loadUrl("about:blank")
                target = null
                result = null
            },
            onBack = onBack,
        )
    }
}

private class TestWebViewClient(
    private val onResult: (TestResult) -> Unit,
    private val onAssetBlocked: () -> Unit,
) : WebViewClient() {

    private val filterBlocklist = Blocklist(
        listOf(
            "pornhub.com", "xvideos.com", "xhamster.com", "onlyfans.com",
            "scrolller.com", "redgifs.com", "chaturbate.com", "stripchat.com",
        ),
    )

    /**
     * Content filtering for every resource loaded inside the app's WebView:
     *  1. Adult hosts / explicit-URL assets -> dropped (no TLS inspection).
     *  2. Image subresources -> bytes are fetched and classified ON-DEVICE
     *     (Yahoo OpenNSFW TFLite): explicit imagery is replaced by a blocked
     *     placeholder; safe images are returned unchanged.
     */
    override fun shouldInterceptRequest(
        view: WebView?,
        request: WebResourceRequest?,
    ): WebResourceResponse? {
        request ?: return null
        val url = request.url
        val decision = WebContentFilter.decide(url.host, url.toString(), filterBlocklist)
        if (decision != WebContentFilter.Decision.ALLOW) {
            if (request.isForMainFrame) {
                onResult(TestResult.Blocked(url.toString(), "blocked by Riy in-app filter"))
            } else {
                onAssetBlocked()
            }
            return blockedResponse(request.isForMainFrame)
        }
        if (request.isForMainFrame) return null
        if (!isImageResource(url.toString())) return null

        ImageClassifier.ensureInit(view?.context ?: return null)
        val bytes = fetchImage(url.toString()) ?: return null
        val nsfw = ImageClassifier.classify(url.toString(), bytes)
        if (nsfw) {
            onAssetBlocked()
            return blockedResponse(mainFrame = false)
        }
        val contentType = guessContentType(url.toString())
        return WebResourceResponse(contentType, null, bytes.inputStream())
    }

    override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
        val url = request?.url ?: return false
        val decision = WebContentFilter.decide(url.host, url.toString(), filterBlocklist)
        if (decision != WebContentFilter.Decision.ALLOW) {
            onResult(TestResult.Blocked(url.toString(), "blocked by Riy in-app filter"))
            return true
        }
        return false
    }

    override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
        onResult(TestResult.Running)
        super.onPageStarted(view, url, favicon)
    }

    override fun onReceivedError(
        view: WebView?,
        request: WebResourceRequest?,
        error: WebResourceError?,
    ) {
        if (request == null || !request.isForMainFrame || error == null) return
        val host = Uri.parse(request.url.toString()).host ?: return
        val description = error.description?.toString().orEmpty()
        val errorCode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) error.errorCode else -1
        if (BlocklistTestHelper.isAdult(host)) {
            if (DNS_BLOCK_ERRORS.any { description.contains(it) } || errorCode == -2) {
                // -2 = ERROR_HOST_LOOKUP (classic DNS failure signature)
                onResult(TestResult.Blocked(request.url.toString(), description))
            } else {
                onResult(TestResult.Failed(request.url.toString(), "$description (code $errorCode)"))
            }
        } else {
            onResult(
                TestResult.Failed(request.url.toString(), description.ifEmpty { "load error $errorCode" }),
            )
        }
        super.onReceivedError(view, request, error)
    }

    override fun onPageFinished(view: WebView?, url: String?) {
        super.onPageFinished(view, url)
        val current = url ?: return
        if (current == "about:blank") return
        val host = Uri.parse(current).host ?: return
        // Only report Loaded if no error already arrived for this navigation;
        // onResult() ignores this when the result is already Blocked/Failed.
        onResult(TestResult.Loaded(current))
    }
}

    /** Test hosts are classified by the same matching logic as the filter. */
    private object BlocklistTestHelper {
        private val blocklist = Blocklist(listOf("pornhub.com", "xvideos.com"))

        fun isAdult(host: String): Boolean = blocklist.contains(host)
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

    /** Fetches image bytes for classification (bounded, on the WebView IO thread). */
    private fun fetchImage(url: String): ByteArray? {
        return try {
            val connection = java.net.URL(url).openConnection() as java.net.HttpURLConnection
            try {
                connection.connectTimeout = 6000
                connection.readTimeout = 6000
                connection.setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android) RiyFilter")
                if (connection.responseCode != 200) return null
                val input = connection.inputStream
                val out = java.io.ByteArrayOutputStream()
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

@Composable
private fun SitePicker(onPick: (String) -> Unit, onBack: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(24.dp),
    ) {
        Text(
            text = stringResource(R.string.test_title),
            style = typography.headlineSmall,
            fontWeight = FontWeight.Bold,
        )
        Spacer(Modifier.height(8.dp))
        Text(
            text = stringResource(R.string.test_description),
            style = typography.bodyMedium,
            color = colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(16.dp))
        val phase = BlockerState.current().phase
        Text(
            text = if (phase == BlockerState.Phase.CONNECTED) {
                stringResource(R.string.test_status_on)
            } else {
                stringResource(R.string.test_status_off)
            },
            style = typography.bodyMedium,
            fontWeight = FontWeight.SemiBold,
            color = if (phase == BlockerState.Phase.CONNECTED) Color(0xFF2E7D32) else colorScheme.error,
        )
        Spacer(Modifier.height(16.dp))
        TEST_SITES.forEach { (url, label) ->
            OutlinedButton(onClick = { onPick(url) }, modifier = Modifier.fillMaxWidth()) {
                Text(label)
            }
            Spacer(Modifier.height(8.dp))
        }
        Spacer(Modifier.weight(1f))
        OutlinedButton(onClick = onBack, modifier = Modifier.fillMaxWidth()) {
            Text(stringResource(R.string.action_back))
        }
    }
}

@Composable
private fun TestRunView(
    webView: WebView,
    target: String,
    result: TestResult?,
    blockedAssets: Int,
    onLoad: (String) -> Unit,
    onBackToPicker: () -> Unit,
    onBack: () -> Unit,
) {
    LaunchedEffect(target) { if (target.isNotBlank()) onLoad(target) }

    Box(modifier = Modifier.fillMaxSize()) {
        AndroidView(
            modifier = Modifier.fillMaxSize(),
            factory = { webView },
        )

        // Bottom status card (hidden while the full blocked overlay shows).
        if (result !is TestResult.Blocked) {
            Card(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .padding(12.dp),
                colors = CardDefaults.cardColors(
                    containerColor = when (result) {
                        is TestResult.Loaded ->
                            if (BlocklistTestHelper.isAdult(Uri.parse(result.url).host ?: "")) {
                                colorScheme.errorContainer
                            } else {
                                colorScheme.primaryContainer
                            }
                        is TestResult.Failed -> colorScheme.surfaceVariant
                        else -> colorScheme.surfaceVariant
                    },
                ),
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        text = when (result) {
                            is TestResult.Running, null -> stringResource(R.string.test_loading)
                            is TestResult.Loaded ->
                                if (BlocklistTestHelper.isAdult(Uri.parse(result.url).host ?: "")) {
                                    stringResource(R.string.test_result_not_blocked)
                                } else {
                                    stringResource(R.string.test_result_allowed)
                                }
                            is TestResult.Failed -> stringResource(R.string.test_result_load_error)
                            is TestResult.Blocked -> stringResource(R.string.blocked_overlay_title)
                        },
                        style = typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                    )
                    if (result is TestResult.Failed) {
                        Spacer(Modifier.height(4.dp))
                        Text(
                            text = (result as TestResult.Failed).detail,
                            style = typography.bodySmall,
                            color = colorScheme.onSurfaceVariant,
                        )
                    }
                    if (blockedAssets > 0) {
                        Spacer(Modifier.height(4.dp))
                        Text(
                            text = stringResource(R.string.test_assets_blocked, blockedAssets),
                            style = typography.bodySmall,
                            color = colorScheme.error,
                        )
                    }
                    Spacer(Modifier.height(8.dp))
                    OutlinedButton(onClick = onBackToPicker) {
                        Text(stringResource(R.string.action_back_to_tests))
                    }
                }
            }
        }

        if (result is TestResult.Blocked) {
            BlockedOverlay(onBack = onBackToPicker)
        }
    }
}

/**
 * The in-app "Content Blocked" screen shown when a test site is blocked.
 * Simple, clean: title, short explanation, Back/Close.
 */
@Composable
fun BlockedOverlay(onBack: () -> Unit) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(colorScheme.background)
            .zIndex(2f),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            modifier = Modifier
                .padding(32.dp)
                .verticalScroll(rememberScrollState()),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Box(
                modifier = Modifier
                    .size(72.dp)
                    .background(colorScheme.errorContainer, CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = stringResource(R.string.blocked_overlay_icon),
                    style = typography.headlineMedium,
                    color = colorScheme.onErrorContainer,
                )
            }
            Spacer(Modifier.height(16.dp))
            Text(
                text = stringResource(R.string.blocked_overlay_title),
                style = typography.headlineSmall,
                fontWeight = FontWeight.Bold,
            )
            Spacer(Modifier.height(8.dp))
            Text(
                text = stringResource(R.string.blocked_overlay_message),
                style = typography.bodyMedium,
                color = colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(24.dp))
            Button(onClick = onBack) {
                Text(stringResource(R.string.blocked_overlay_close))
            }
        }
    }
}
