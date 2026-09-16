package com.vishal.riy.ui.browser

import android.os.Build
import android.provider.Settings
import android.view.ViewGroup
import android.webkit.WebView
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme.colorScheme
import androidx.compose.material3.MaterialTheme.typography
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.vishal.riy.R

/**
 * Minimal protected browser: address/search bar, back/forward/refresh/home,
 * a loading indicator, the WebView, and a native blocked-page state.
 *
 * Every blocked decision shown here was already enforced inside
 * [ProtectedWebViewClient] (the request was answered locally and never went
 * to the network) — this UI only reports it.
 */
@Composable
fun BrowserScreen(
    state: BrowserUiState,
    webView: WebView,
    onAddressSubmit: (String) -> Unit,
    onBack: () -> Unit,
    onForward: () -> Unit,
    onRefresh: () -> Unit,
    onHome: () -> Unit,
    onDismissBlocked: () -> Unit,
    onOpenProtection: () -> Unit,
    modifier: Modifier = Modifier,
) {
    BackHandler(enabled = state.blocked != null || state.canGoBack) {
        when {
            state.blocked != null -> onDismissBlocked()
            state.canGoBack -> onBack()
        }
    }

    Box(modifier = modifier.fillMaxSize()) {
        Column(modifier = Modifier.fillMaxSize()) {
            BrowserToolbar(
                state = state,
                onAddressSubmit = onAddressSubmit,
                onBack = onBack,
                onForward = onForward,
                onRefresh = onRefresh,
                onHome = onHome,
                onOpenProtection = onOpenProtection,
            )
            if (state.isLoading) {
                LinearProgressIndicator(
                    progress = { state.progress / 100f },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            Box(modifier = Modifier.fillMaxSize()) {
                AndroidView(
                    modifier = Modifier.fillMaxSize(),
                    factory = { webView },
                )
                if (state.assetsBlocked > 0) {
                    Text(
                        text = stringResource(R.string.browser_assets_blocked, state.assetsBlocked),
                        style = typography.bodySmall,
                        color = colorScheme.error,
                        modifier = Modifier
                            .align(Alignment.BottomStart)
                            .padding(8.dp)
                            .background(colorScheme.surface.copy(alpha = 0.85f), CircleShape)
                            .padding(horizontal = 10.dp, vertical = 4.dp),
                    )
                }
            }
        }

        state.blocked?.let { blocked ->
            BlockedScreen(blocked = blocked, onBack = onDismissBlocked, onHome = onHome)
        }
    }
}

@Composable
private fun BrowserToolbar(
    state: BrowserUiState,
    onAddressSubmit: (String) -> Unit,
    onBack: () -> Unit,
    onForward: () -> Unit,
    onRefresh: () -> Unit,
    onHome: () -> Unit,
    onOpenProtection: () -> Unit,
) {
    var editing by remember { mutableStateOf(false) }
    var typed by remember { mutableStateOf("") }
    val keyboard = LocalSoftwareKeyboardController.current
    val context = LocalContext.current
    var menuOpen by remember { mutableStateOf(false) }

    Column {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 4.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onBack, enabled = state.canGoBack) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.browser_content_back))
            }
            IconButton(onClick = onForward, enabled = state.canGoForward) {
                Icon(Icons.AutoMirrored.Filled.ArrowForward, contentDescription = stringResource(R.string.browser_content_forward))
            }

            // Address/search bar. While focused it holds what the user typed;
            // otherwise it follows the page URL.
            val address = if (editing) typed else state.addressText
            OutlinedTextField(
                value = address,
                onValueChange = { typed = it; editing = true },
                modifier = Modifier
                    .weight(1f)
                    .onFocusChanged { focus -> if (!focus.isFocused) editing = false },
                singleLine = true,
                placeholder = { Text(stringResource(R.string.browser_address_hint)) },
                leadingIcon = { Icon(Icons.Filled.Lock, contentDescription = null) },
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Go),
                keyboardActions = KeyboardActions(onGo = {
                    onAddressSubmit(typed)
                    editing = false
                    keyboard?.hide()
                }),
            )

            IconButton(onClick = onRefresh) {
                Icon(Icons.Filled.Refresh, contentDescription = stringResource(R.string.browser_content_refresh))
            }
            IconButton(onClick = onHome) {
                Icon(Icons.Filled.Home, contentDescription = stringResource(R.string.browser_content_home))
            }
            Box {
                IconButton(onClick = { menuOpen = true }) {
                    Icon(Icons.Filled.MoreVert, contentDescription = stringResource(R.string.browser_content_menu))
                }
                DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.browser_set_default)) },
                        onClick = {
                            menuOpen = false
                            openDefaultBrowserSettings(context)
                        },
                    )
                    HorizontalDivider()
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.browser_open_protection)) },
                        onClick = {
                            menuOpen = false
                            onOpenProtection()
                        },
                    )
                }
            }
        }
        state.errorMessage?.let { msg ->
            Text(
                text = msg,
                style = typography.bodySmall,
                color = colorScheme.error,
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 2.dp),
            )
        }
    }
}

/**
 * Asks the system to let the user pick riy as the default browser. On
 * Android 10+ this opens the role request (the same picker used when an app
 * first offers to open links). On older versions it opens the default-apps
 * settings page. Chrome is never removed or disabled — the user chooses.
 */
private fun openDefaultBrowserSettings(context: android.content.Context) {
    runCatching {
        val intent = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val roleManager = context.getSystemService(android.app.role.RoleManager::class.java)
            roleManager.createRequestRoleIntent(android.app.role.RoleManager.ROLE_BROWSER)
        } else {
            android.content.Intent(Settings.ACTION_MANAGE_DEFAULT_APPS_SETTINGS)
                .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(intent)
    }
}

/**
 * The native "Adult Search Blocked" / "Content Blocked" screen, shown the
 * moment the pipeline stops a request. Back / Go Home continue browsing.
 */
@Composable
private fun BlockedScreen(
    blocked: BlockedState,
    onBack: () -> Unit,
    onHome: () -> Unit,
) {
    val (title, detail) = when (blocked) {
        is BlockedState.AdultSearch -> stringResource(R.string.adult_search_blocked_title) to
            stringResource(R.string.adult_search_blocked_detail)
        is BlockedState.Content -> stringResource(R.string.blocked_overlay_title) to
            stringResource(R.string.blocked_overlay_message)
    }
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(colorScheme.background),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            modifier = Modifier
                .padding(32.dp)
                .verticalScroll(rememberScrollState()),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Box(
                modifier = Modifier
                    .size(72.dp)
                    .background(colorScheme.errorContainer, CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    Icons.Filled.Lock,
                    contentDescription = null,
                    tint = colorScheme.onErrorContainer,
                    modifier = Modifier.size(32.dp),
                )
            }
            Spacer(Modifier.height(16.dp))
            Text(title, style = typography.headlineSmall, fontWeight = FontWeight.Bold)
            Text(
                text = stringResource(R.string.adult_search_blocked_message),
                style = typography.bodyMedium,
                color = colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(4.dp))
            Text(
                text = detail,
                style = typography.bodySmall,
                color = colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(24.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                TextButton(onClick = onBack) { Text(stringResource(R.string.action_back)) }
                TextButton(onClick = onHome) { Text(stringResource(R.string.blocked_action_home)) }
            }
        }
    }
}
