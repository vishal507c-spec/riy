package com.vishal.riy.ui

import android.annotation.SuppressLint
import android.net.Uri
import android.view.ViewGroup
import android.webkit.WebView
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
import com.vishal.riy.blocker.ProtectedWebViewClient

private val TEST_SITES = listOf(    "https://www.google.com/search?q=hot+photo" to "Adult search block check: 'hot photo' (Google)",
    "https://www.bing.com/search?q=sexy+photo" to "Adult search block check: 'sexy photo' (Bing)",
    "https://www.google.com/search?q=adult+education" to "Allow check: 'adult education' (Google)",
    "https://www.google.com/search?q=hot+photo&tbm=isch" to "Google Images 'hot photo' (classifier check)",
    "https://www.google.com/search?q=sexy+photo&tbm=isch" to "Google Images 'sexy photo' (classifier check)",
    "https://www.google.com/search?q=hot+girl&tbm=isch" to "Google Images 'hot girl' (classifier check)",
    "https://www.google.com/search?q=sexy+girl&tbm=isch" to "Google Images 'sexy girl' (classifier check)",
    "https://www.google.com/search?q=nude&tbm=isch" to "Google Images 'nude' (SafeSearch + classifier)",
    "https://www.google.com/search?q=fashion+dress&tbm=isch" to "Google Images 'fashion dress' (FP check)",
    "https://www.google.com/search?q=porn" to "Google explicit search (SafeSearch check)",
    "https://www.google.com/search?q=porn&tbm=isch" to "Google Images explicit (SafeSearch check)",
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

/**
 * Hosts the in-app filter is asked to recognise during tests. Deliberately a
 * SMALL subset so that a direct load of an adult site is stopped by the DNS
 * layer (the honest thing under test) rather than pre-empted in-app.
 */
private val TEST_BLOCKLIST = Blocklist(
    listOf(
        "pornhub.com", "xvideos.com", "xhamster.com", "onlyfans.com",
        "scrolller.com", "redgifs.com", "chaturbate.com", "stripchat.com",
    ),
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
fun ProtectionTestScreen(onBack: () -> Unit, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    var target by remember { mutableStateOf<String?>(null) }
    val testState = remember { TestRunState() }

    val webView = remember {
        WebView(context).apply {
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
            )
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            // The full protection pipeline (search-keyword / host / image
            // classifier). blockMainFrameHosts = false keeps this an HONEST
            // test: an adult site loaded directly must be stopped by the DNS
            // layer, not by the in-app URL filter.
            webViewClient = ProtectedWebViewClient(
                blocklist = TEST_BLOCKLIST,
                blockMainFrameHosts = false,
                onEvent = testState::handleEvent,
            )
        }
    }
    DisposableEffect(Unit) { onDispose { webView.destroy() } }

    when {
        target == null -> SitePicker(
            onPick = { url ->
                testState.reset()
                testState.submit(TestResult.Running)
                target = url
            },
            onBack = onBack,
            modifier = modifier,
        )

        else -> TestRunView(
            webView = webView,
            target = target ?: "",
            result = testState.result,
            blockedAssets = testState.blockedAssets,
            onLoad = { url -> webView.loadUrl(url) },
            onBackToPicker = {
                webView.stopLoading()
                webView.loadUrl("about:blank")
                target = null
                testState.reset()
            },
            onBack = onBack,
            modifier = modifier,
        )
    }
}

/**
 * Holds the live result of the running protection test and maps
 * protection-pipeline events into honest outcomes. The FIRST definitive
 * result wins; later duplicates for the same run are ignored.
 */
private class TestRunState {
    var result by mutableStateOf<TestResult?>(null)
        private set
    var blockedAssets by mutableIntStateOf(0)
        private set

    fun reset() {
        result = null
        blockedAssets = 0
    }

    fun submit(testResult: TestResult) {
        if (result is TestResult.Running || result == null) result = testResult
    }

    /** ProtectionEvent -> TestResult, mirroring the documented test contract. */
    fun handleEvent(event: ProtectedWebViewClient.ProtectionEvent) {
        when (event) {
            is ProtectedWebViewClient.ProtectionEvent.PageStarted ->
                submit(TestResult.Running)

            is ProtectedWebViewClient.ProtectionEvent.PageFinished ->
                if (event.url != "about:blank") submit(TestResult.Loaded(event.url))

            is ProtectedWebViewClient.ProtectionEvent.PageError -> {
                val host = Uri.parse(event.url).host ?: return
                if (BlocklistTestHelper.isAdult(host)) {
                    if (DNS_BLOCK_ERRORS.any { event.description.contains(it) } || event.errorCode == -2) {
                        // -2 = ERROR_HOST_LOOKUP (classic DNS-failure signature)
                        submit(TestResult.Blocked(event.url, event.description))
                    } else {
                        submit(TestResult.Failed(event.url, "${event.description} (code ${event.errorCode})"))
                    }
                } else {
                    submit(
                        TestResult.Failed(
                            event.url,
                            event.description.ifEmpty { "load error ${event.errorCode}" },
                        ),
                    )
                }
            }

            is ProtectedWebViewClient.ProtectionEvent.AdultSearchBlocked ->
                submit(TestResult.Blocked(event.url, "adult search query blocked"))

            is ProtectedWebViewClient.ProtectionEvent.HostBlocked ->
                submit(TestResult.Blocked(event.url, "blocked by Riy in-app filter"))

            is ProtectedWebViewClient.ProtectionEvent.AssetBlocked -> blockedAssets++
        }
    }
}

/** Test hosts are classified by the same matching logic as the filter. */
private object BlocklistTestHelper {
    private val blocklist = Blocklist(listOf("pornhub.com", "xvideos.com"))

    fun isAdult(host: String): Boolean = blocklist.contains(host)
}

@Composable
private fun SitePicker(
    onPick: (String) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
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
    modifier: Modifier = Modifier,
) {
    LaunchedEffect(target) { if (target.isNotBlank()) onLoad(target) }

    Box(modifier = modifier.fillMaxSize()) {
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
            BlockedOverlay(
                onBack = onBackToPicker,
                title = if (result.detail.contains("adult search")) {
                    stringResource(R.string.adult_search_blocked_title)
                } else {
                    stringResource(R.string.blocked_overlay_title)
                },
            )
        }
    }
}

/**
 * The in-app "Content Blocked" screen shown when a test site is blocked.
 * Simple, clean: title, short explanation, Back/Close.
 */
@Composable
fun BlockedOverlay(onBack: () -> Unit, title: String = stringResource(R.string.blocked_overlay_title)) {
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
                text = title,
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
