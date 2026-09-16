package com.vishal.riy.ui.browser

import android.app.Application
import android.webkit.WebChromeClient
import android.webkit.WebView
import androidx.lifecycle.AndroidViewModel
import com.vishal.riy.blocker.BlockerState
import com.vishal.riy.blocker.ProtectedWebViewClient
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/** What the browser is showing instead of a page (null = normal browsing). */
sealed interface BlockedState {
    /** Adult search query — the results page was never fetched. */
    data class AdultSearch(val url: String) : BlockedState
    /** Adult host / explicit URL (direct-website protection). */
    data class Content(val url: String) : BlockedState
}

data class BrowserUiState(
    val addressText: String = "",
    val currentUrl: String = "",
    val isLoading: Boolean = false,
    val progress: Int = 0,
    val canGoBack: Boolean = false,
    val canGoForward: Boolean = false,
    val blocked: BlockedState? = null,
    /** Cumulative count of adult images/assets removed from rendered pages. */
    val assetsBlocked: Int = 0,
    /** Shown briefly when a page fails to load for a non-protection reason. */
    val errorMessage: String? = null,
)

/**
 * State + actions for the protected browser. Owns no View itself: [bindWebView]
 * attaches the single WebView created by the UI (kept alive across tab
 * switches) and wires it to the [ProtectedWebViewClient] pipeline.
 *
 * Protection never depends on this ViewModel: every block decision is made
 * inside [ProtectedWebViewClient] (which answers the request with 403 before
 * any byte of a blocked page is fetched). This class only reflects those
 * decisions to the UI.
 */
class BrowserViewModel(application: Application) : AndroidViewModel(application) {

    private var webView: WebView? = null
    private val blocklist = ProtectedWebViewClient.loadBlocklist(application)

    private val _uiState = MutableStateFlow(BrowserUiState())
    val uiState: StateFlow<BrowserUiState> = _uiState.asStateFlow()

    /** True while system-wide DNS protection is active (additive layer). */
    val protectionOn: Boolean get() = BlockerState.current().phase == BlockerState.Phase.CONNECTED

    /**
     * Attaches [webView] to the protection pipeline. Idempotent; called once
     * per WebView instance. A freshly bound WebView restores the last page
     * (e.g. after rotation) or opens Home.
     */
    fun bindWebView(webView: WebView) {
        if (this.webView === webView) return
        this.webView = webView
        webView.apply {
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.cacheMode = android.webkit.WebSettings.LOAD_DEFAULT
            webViewClient = ProtectedWebViewClient(blocklist, ::handleEvent)
            webChromeClient = object : WebChromeClient() {
                override fun onProgressChanged(view: WebView?, newProgress: Int) {
                    _uiState.update {
                        it.copy(progress = newProgress, isLoading = newProgress in 1..99)
                    }
                }
            }
        }
        val saved = _uiState.value.currentUrl
        when {
            saved.isNotBlank() && webView.url.isNullOrBlank() -> webView.loadUrl(saved)
            else -> webView.loadUrl(AddressBarResolver.HOME_URL)
        }
    }

    /** Address-bar submit: search-vs-URL is resolved, then protection decides. */
    fun loadInput(input: String) {
        resetBlocked()
        _uiState.update { it.copy(errorMessage = null) }
        webView?.loadUrl(AddressBarResolver.toLoadTarget(input))
    }

    /** Loads a raw URL handed to riy by the system (default-browser intent). */
    fun loadDirect(url: String) {
        if (url.isBlank()) return
        resetBlocked()
        _uiState.update {
            it.copy(errorMessage = null, addressText = AddressBarResolver.displayText(url))
        }
        webView?.loadUrl(url)
    }

    fun goBack() {
        resetBlocked()
        webView?.takeIf { it.canGoBack() }?.goBack()
    }

    fun goForward() {
        resetBlocked()
        webView?.takeIf { it.canGoForward() }?.goForward()
    }

    fun refresh() {
        resetBlocked()
        webView?.reload()
    }

    fun goHome() {
        resetBlocked()
        _uiState.update { it.copy(errorMessage = null) }
        webView?.loadUrl(AddressBarResolver.HOME_URL)
    }

    /**
     * Dismisses the blocked screen (Back button / system back): clears the
     * overlay and steps back in history — the blocked 403 page is the current
     * entry, so this returns to the page before it. Falls back to Home when
     * there is nothing to go back to.
     */
    fun clearBlocked() {
        val view = webView
        _uiState.update { it.copy(blocked = null) }
        if (view == null) return
        if (!view.url.isNullOrBlank() && view.canGoBack()) {
            view.goBack()
        } else {
            view.loadUrl(AddressBarResolver.HOME_URL)
        }
    }

    /** Drops any blocked-screen state WITHOUT touching navigation. */
    private fun resetBlocked() {
        _uiState.update { it.copy(blocked = null) }
    }

    // --------------------------------------------------------------- events

    private fun handleEvent(event: ProtectedWebViewClient.ProtectionEvent) {
        when (event) {
            is ProtectedWebViewClient.ProtectionEvent.PageStarted -> _uiState.update {
                it.copy(
                    isLoading = true,
                    errorMessage = null,
                    currentUrl = event.url,
                    addressText = AddressBarResolver.displayText(event.url),
                    canGoBack = webView?.canGoBack() == true,
                    canGoForward = webView?.canGoForward() == true,
                )
            }

            is ProtectedWebViewClient.ProtectionEvent.PageFinished -> _uiState.update {
                it.copy(
                    isLoading = false,
                    progress = 100,
                    currentUrl = event.url,
                    addressText = AddressBarResolver.displayText(event.url),
                    canGoBack = webView?.canGoBack() == true,
                    canGoForward = webView?.canGoForward() == true,
                )
            }

            is ProtectedWebViewClient.ProtectionEvent.PageError -> _uiState.update {
                it.copy(
                    isLoading = false,
                    errorMessage = event.description.ifBlank { "page could not load (code ${event.errorCode})" },
                    canGoBack = webView?.canGoBack() == true,
                    canGoForward = webView?.canGoForward() == true,
                )
            }

            is ProtectedWebViewClient.ProtectionEvent.AdultSearchBlocked -> _uiState.update {
                it.copy(isLoading = false, blocked = BlockedState.AdultSearch(event.url))
            }

            is ProtectedWebViewClient.ProtectionEvent.HostBlocked -> _uiState.update {
                it.copy(isLoading = false, blocked = BlockedState.Content(event.url))
            }

            is ProtectedWebViewClient.ProtectionEvent.AssetBlocked -> _uiState.update {
                it.copy(assetsBlocked = it.assetsBlocked + 1)
            }
        }
    }
}
