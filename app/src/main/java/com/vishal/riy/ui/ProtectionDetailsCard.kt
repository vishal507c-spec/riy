package com.vishal.riy.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme.colorScheme
import androidx.compose.material3.MaterialTheme.typography
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.vishal.riy.R
import com.vishal.riy.protection.enforcement.EnforcementStatus
import com.vishal.riy.protection.ui.ProtectionUiState

/**
 * THE one optional "Protection Details" card, shared by the main screen and the
 * lock screen so the detail section can never render two different ways.
 *
 * Every technical state is translated into normal language here — "Network
 * protection — Active", never "VpnService — CONNECTED"; "Device protection —
 * Active", never "DevicePolicyManager — TRUE". Nothing shows a package name, a
 * domain, an event count, a log line, a graph or a percentage.
 *
 * Rendering only: the card carries no state of its own and no control. It
 * cannot disable, pause, bypass or reset anything, because it is handed no
 * object that could.
 */
@Composable
fun ProtectionDetailsCard(state: ProtectionUiState) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = colorScheme.surface),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(
                text = stringResource(R.string.details_section),
                style = typography.labelLarge,
                color = colorScheme.onSurfaceVariant,
            )
            DetailLine(
                labelRes = R.string.details_device,
                value = stringResource(
                    if (state.deviceOwnerActive && state.uninstallProtectionActive) R.string.state_active
                    else R.string.state_unavailable,
                ),
                ok = state.deviceOwnerActive && state.uninstallProtectionActive,
            )
            DetailLine(
                labelRes = R.string.details_apps,
                value = appRestrictionLine(state),
                ok = state.enforcementVerified,
            )
            DetailLine(
                labelRes = R.string.details_network,
                value = stringResource(R.string.state_active),
                ok = state.protectionActive,
            )
            DetailLine(
                labelRes = R.string.details_integrity,
                value = stringResource(
                    if (state.integrityVerified) R.string.state_active else R.string.state_unavailable,
                ),
                ok = state.integrityVerified,
            )
        }
    }
}

@Composable
private fun DetailLine(labelRes: Int, value: String, ok: Boolean) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Box(
            modifier = Modifier
                .size(10.dp)
                .background(if (ok) Color(0xFF2E7D32) else colorScheme.error, CircleShape),
        )
        Text(
            text = stringResource(labelRes),
            style = typography.bodyMedium,
            modifier = Modifier.weight(1f),
        )
        Text(
            text = value,
            style = typography.bodyMedium,
            fontWeight = FontWeight.Medium,
            color = colorScheme.onSurfaceVariant,
        )
    }
}

/** The app-restriction line, translated from the real enforcement read-back. */
@Composable
private fun appRestrictionLine(state: ProtectionUiState): String = when {
    state.enforcementMismatch -> stringResource(R.string.enforcement_mismatch)
    state.isRecovering -> stringResource(R.string.state_applying)
    state.enforcementStatus == EnforcementStatus.ACTIVE_RESTRICTED ->
        stringResource(R.string.state_active)
    state.enforcementStatus == EnforcementStatus.NORMAL ->
        stringResource(R.string.state_active)
    else -> stringResource(R.string.enforcement_pending)
}
