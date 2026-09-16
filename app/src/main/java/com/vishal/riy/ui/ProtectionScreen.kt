package com.vishal.riy.ui

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.net.VpnService
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MaterialTheme.colorScheme
import androidx.compose.material3.MaterialTheme.typography
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.vishal.riy.BuildConfig
import com.vishal.riy.R
import com.vishal.riy.awareness.AwarenessSnapshot
import com.vishal.riy.blocker.BlockerState

/**
 * Main screen: app name, real-time Protection ON/OFF status (driven by the
 * VPN service state, never faked), enable/disable/test actions and a small
 * settings/info section.
 */
@Composable
fun ProtectionScreen(
    onOpenProtectionTest: () -> Unit,
    onOpenBrowser: () -> Unit,
    awarenessSnapshot: AwarenessSnapshot? = null,
    modifier: Modifier = Modifier,
    viewModel: ProtectionViewModel = viewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current

    // VPN consent (system dialog) — on approval we actually start the service.
    val vpnConsentLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            viewModel.enableProtection(context)
        }
    }

    // Notification permission (Android 13+); optional — service works either way.
    val notificationPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { }

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Spacer(Modifier.height(8.dp))
        Text(
            text = stringResource(R.string.app_name),
            style = typography.headlineLarge,
            fontWeight = FontWeight.Bold,
        )
        Text(
            text = stringResource(R.string.app_tagline),
            style = typography.bodyMedium,
            color = colorScheme.onSurfaceVariant,
        )

        Spacer(Modifier.height(8.dp))
        ProtectionStatusCard(state.phase, state.failureReason)

        Spacer(Modifier.height(4.dp))
        Text(
            text = stringResource(R.string.blocked_counter_label, state.blockedCount),
            style = typography.bodyMedium,
            color = colorScheme.onSurfaceVariant,
        )

        // Minimal awareness/progress metrics (no streaks, no shaming).
        awarenessSnapshot?.let { snapshot ->
            AwarenessProgressSection(snapshot)
        }

        Spacer(Modifier.height(4.dp))

        Button(
            onClick = onOpenBrowser,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(stringResource(R.string.action_open_browser))
        }

        Button(
            onClick = {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                }
                val consentIntent = VpnService.prepare(context)
                if (consentIntent != null) {
                    // System VPN consent dialog; service starts on approval.
                    vpnConsentLauncher.launch(consentIntent)
                } else {
                    viewModel.enableProtection(context)
                }
            },
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(stringResource(R.string.action_enable_protection))
        }

        OutlinedButton(
            onClick = { viewModel.disableProtection(context) },
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(stringResource(R.string.action_disable_protection))
        }

        OutlinedButton(
            onClick = onOpenProtectionTest,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(stringResource(R.string.action_test_protection))
        }

        Spacer(Modifier.height(8.dp))
        HorizontalDivider()
        Spacer(Modifier.height(4.dp))

        SettingsSection()

        Text(
            text = stringResource(R.string.app_version_label, BuildConfig.VERSION_NAME),
            style = typography.bodySmall,
            color = colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun ProtectionStatusCard(phase: BlockerState.Phase, failureReason: String?) {
    val (label, containerColor, contentColor) = when (phase) {
        BlockerState.Phase.CONNECTED -> StatusStyle(
            stringResource(R.string.status_protection_on),
            colorScheme.primaryContainer,
            colorScheme.onPrimaryContainer,
        )
        BlockerState.Phase.CONNECTING -> StatusStyle(
            stringResource(R.string.status_connecting),
            colorScheme.tertiaryContainer,
            colorScheme.onTertiaryContainer,
        )
        BlockerState.Phase.FAILED -> StatusStyle(
            stringResource(R.string.status_protection_failed),
            MaterialTheme.colorScheme.errorContainer,
            MaterialTheme.colorScheme.onErrorContainer,
        )
        else -> StatusStyle(
            stringResource(R.string.status_protection_off),
            colorScheme.surfaceVariant,
            colorScheme.onSurfaceVariant,
        )
    }
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = containerColor),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 28.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            StatusDot(phase)
            Spacer(Modifier.height(12.dp))
            Text(label, style = typography.headlineMedium, fontWeight = FontWeight.Bold, color = contentColor)
            Spacer(Modifier.height(6.dp))
            Text(
                text = when (phase) {
                    BlockerState.Phase.CONNECTED -> stringResource(R.string.status_detail_active)
                    BlockerState.Phase.CONNECTING -> stringResource(R.string.status_detail_connecting)
                    BlockerState.Phase.FAILED -> failureReason
                        ?: stringResource(R.string.status_detail_failed)
                    else -> stringResource(R.string.status_detail_off)
                },
                style = typography.bodySmall,
                color = contentColor,
            )
        }
    }
}

private data class StatusStyle(val label: String, val container: Color, val content: Color)

@Composable
private fun StatusDot(phase: BlockerState.Phase) {
    val dotColor = when (phase) {
        BlockerState.Phase.CONNECTED -> Color(0xFF2E7D32)
        BlockerState.Phase.CONNECTING -> Color(0xFFF9A825)
        BlockerState.Phase.FAILED -> MaterialTheme.colorScheme.error
        else -> Color(0xFF9E9E9E)
    }
    Box(
        modifier = Modifier
            .size(18.dp)
            .background(dotColor, CircleShape),
    )
}

@Composable
private fun SettingsSection() {
    val context = LocalContext.current
    Column(modifier = Modifier.fillMaxWidth()) {
        Text(
            text = stringResource(R.string.settings_title),
            style = typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
        )
        Spacer(Modifier.height(4.dp))
        Text(
            text = stringResource(R.string.settings_info_lines),
            style = typography.bodySmall,
            color = colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(8.dp))
        OutlinedButton(onClick = {
            runCatching {
                context.startActivity(
                    Intent(Settings.ACTION_VPN_SETTINGS)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                )
            }
        }) {
            Text(stringResource(R.string.settings_open_vpn))
        }
    }
}
