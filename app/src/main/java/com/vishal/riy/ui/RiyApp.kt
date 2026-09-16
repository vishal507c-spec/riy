package com.vishal.riy.ui

import android.webkit.WebView
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Public
import androidx.compose.material.icons.filled.Security
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.vishal.riy.R
import com.vishal.riy.awareness.AwarenessSnapshot
import com.vishal.riy.awareness.AwarenessViewModel
import com.vishal.riy.awareness.Phase
import com.vishal.riy.ui.browser.BlockedState
import com.vishal.riy.ui.browser.BrowserScreen
import com.vishal.riy.ui.browser.BrowserViewModel

private enum class RiyScreen { Browser, Protection }

/**
 * Root composable. Two destinations: the protected browser and the
 * protection dashboard. The single WebView is created HERE (not inside
 * BrowserScreen) so the open page survives tab switches; it is destroyed
 * only when the app's UI leaves composition.
 */
@Composable
fun RiyApp(
    externalUrl: String? = null,
    onExternalUrlHandled: () -> Unit = {},
) {
    RiyTheme {
        val context = LocalContext.current
        val browserViewModel: BrowserViewModel = viewModel()
        val awarenessViewModel: AwarenessViewModel = viewModel()
        val browserState by browserViewModel.uiState.collectAsStateWithLifecycle()
        val awarenessState by awarenessViewModel.state.collectAsStateWithLifecycle()
        var screen by rememberSaveable { mutableStateOf(RiyScreen.Browser) }
        var showTest by rememberSaveable { mutableStateOf(false) }

        // One WebView for the app's lifetime; the protection pipeline is
        // attached once by the ViewModel.
        val webView = remember { WebView(context) }
        DisposableEffect(Unit) {
            browserViewModel.bindWebView(webView)
            onDispose { webView.destroy() }
        }

        // A link the system handed to riy (default-browser intent): load it
        // in the protected WebView, then let the Activity forget it.
        LaunchedEffect(externalUrl) {
            val url = externalUrl ?: return@LaunchedEffect
            if (screen != RiyScreen.Browser) screen = RiyScreen.Browser
            // While a lock deadline is live (pause / trigger / locked) there is â€” the lock
            // nothing to load - the awareness overlay covers the browser anyway,
            // so the URL is simply dropped rather than fetched behind it.
            if (awarenessState.remainingMillis <= 0) browserViewModel.loadDirect(url)
            onExternalUrlHandled()
        }

        // The existing pipeline already blocked the adult search (the results
        // page was never fetched). This is where riy turns that block into a
        // pause: record the detection, start the awareness flow and apply the
        // progressive lock. The block decision itself is not touched here.
        LaunchedEffect(browserState.blocked) {
            val blocked = browserState.blocked
            if (blocked is BlockedState.AdultSearch) {
                awarenessViewModel.onAdultSearchDetected(blocked.url)
            }
        }

        // Keep the browser's own blocked overlay from lingering under (or
        // after) the lock: clear it once the lock takes over, and again once
        // the lock has expired.
        LaunchedEffect(awarenessState.phase) {
            if (awarenessState.phase == Phase.LOCKED || awarenessState.phase == Phase.NONE) {
                if (browserState.blocked != null) browserViewModel.clearBlocked()
            }
        }

        Scaffold(
            modifier = Modifier.fillMaxSize(),
            bottomBar = {
                // The test screen is a full-screen flow; hide the tab bar there.
                if (!showTest) {
                    NavigationBar {
                        RiyScreen.entries.forEach { entry ->
                            NavigationBarItem(
                                selected = screen == entry && !showTest,
                                onClick = { screen = entry },
                                icon = {
                                    Icon(
                                        if (entry == RiyScreen.Browser) Icons.Filled.Public
                                        else Icons.Filled.Security,
                                        contentDescription = null,
                                    )
                                },
                                label = {
                                    Text(
                                        if (entry == RiyScreen.Browser) stringResource(R.string.browser_tab)
                                        else stringResource(R.string.protection_tab),
                                    )
                                },
                            )
                        }
                    }
                }
            },
        ) { padding ->
            when {
                showTest -> ProtectionTestScreen(
                    onBack = { showTest = false },
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(padding),
                )

                screen == RiyScreen.Browser -> {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(padding),
                    ) {
                        BrowserScreen(
                            state = browserState,
                            webView = webView,
                            onAddressSubmit = browserViewModel::loadInput,
                            onBack = browserViewModel::goBack,
                            onForward = browserViewModel::goForward,
                            onRefresh = browserViewModel::refresh,
                            onHome = browserViewModel::goHome,
                            onDismissBlocked = browserViewModel::clearBlocked,
                            onOpenProtection = { screen = RiyScreen.Protection },
                        )

                        // Awareness flow overlays the browser without a way to
                        // bypass it: a calm pause, an optional trigger tag, then
                        // the lock screen with its countdown.
                        when (awarenessState.phase) {
                            Phase.PAUSE -> AwarenessPauseOverlay(awarenessState)
                            Phase.TRIGGER -> TriggerSelectionOverlay(
                                state = awarenessState,
                                onSelected = awarenessViewModel::onTriggerSelected,
                                onSkip = awarenessViewModel::onTriggerSkipped,
                            )
                            Phase.LOCKED -> LockOverlay(awarenessState)
                            Phase.NONE -> Unit
                        }
                    }
                }

                else -> ProtectionScreen(
                    onOpenProtectionTest = { showTest = true },
                    onOpenBrowser = { screen = RiyScreen.Browser },
                    awarenessSnapshot = awarenessState,
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(padding),
                )
            }
        }
    }
}
