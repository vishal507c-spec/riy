package com.vishal.riy.ui

import android.app.Activity
import android.net.VpnService
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
import androidx.compose.material3.MaterialTheme.colorScheme
import androidx.compose.material3.MaterialTheme.typography
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
import com.vishal.riy.blocker.BlockerState

/**
 * The one and only screen of riy: app name, a heading, and the REAL protection
 * status (driven by the VPN service state, never faked). The single action is
 * enabling protection, which goes through the system VPN consent dialog.
 */
@Composable
fun ProtectionScreen(
    modifier: Modifier = Modifier,
    viewModel: ProtectionViewModel = viewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current

    // VPN consent (system dialog) — on approval the service actually starts.
    val vpnConsentLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            viewModel.enableProtection(context)
        }
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Spacer(Modifier.height(16.dp))
        Text(
            text = stringResource(R.string.app_shield),
            style = typography.displayLarge,
        )
        Text(
            text = stringResource(R.string.app_name),
            style = typography.headlineLarge,
            fontWeight = FontWeight.Bold,
        )
        Text(
            text = stringResource(R.string.lock_protection_active),
            style = typography.titleLarge,
            fontWeight = FontWeight.SemiBold,
        )

        Spacer(Modifier.height(8.dp))
        ProtectionStatusCard(state.phase, state.failureReason)

        Spacer(Modifier.height(8.dp))
        Button(
            onClick = {
                val consentIntent = VpnService.prepare(context)
                if (consentIntent != null) {
                    vpnConsentLauncher.launch(consentIntent)
                } else {
                    viewModel.enableProtection(context)
                }
            },
            enabled = state.phase != BlockerState.Phase.CONNECTING,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(stringResource(R.string.action_enable_protection))
        }

        Spacer(Modifier.height(8.dp))
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
            colorScheme.errorContainer,
            colorScheme.onErrorContainer,
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
        BlockerState.Phase.FAILED -> colorScheme.error
        else -> Color(0xFF9E9E9E)
    }
    Box(
        modifier = Modifier
            .size(18.dp)
            .background(dotColor, CircleShape),
    )
}
