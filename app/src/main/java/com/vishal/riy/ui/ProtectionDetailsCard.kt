package com.vishal.riy.ui

import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme.colorScheme
import androidx.compose.material3.MaterialTheme.typography
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.vishal.riy.R
import com.vishal.riy.blocker.ShieldStatus
import com.vishal.riy.protection.enforcement.EnforcementStatus
import com.vishal.riy.protection.ui.ProtectionUiState

/**
 * The optional Protection Details card, shared by the dashboard and the lock
 * screen. Every technical state is translated to plain language; no package
 * name, domain, log, count or percentage is ever shown.
 */
@Composable
fun ProtectionDetailsCard(state: ProtectionUiState) {
    SectionCard {
        SectionLabel(stringResource(R.string.details_section))
        Spacer(Modifier.height(10.dp))

        DetailLine(
            label = stringResource(R.string.details_device),
            value = stringResource(
                if (state.deviceOwnerActive && state.uninstallProtectionActive) R.string.state_active
                else R.string.state_unavailable,
            ),
            ok = state.deviceOwnerActive && state.uninstallProtectionActive,
        )
        DetailLine(
            label = stringResource(R.string.details_apps),
            value = appRestrictionLine(state),
            ok = state.enforcementVerified,
        )
        DetailLine(
            label = stringResource(R.string.details_network),
            value = networkLine(state),
            ok = state.filteringVerified,
        )
        if (state.encryptedDnsBypassPossible) {
            Spacer(Modifier.height(6.dp))
            Text(
                text = stringResource(R.string.filter_encrypted_dns_caveat),
                style = typography.bodySmall,
                color = colorScheme.onSurfaceVariant,
            )
        }
        Spacer(Modifier.height(6.dp))
        DetailLine(
            label = stringResource(R.string.details_integrity),
            value = stringResource(
                if (state.integrityVerified) R.string.state_active else R.string.state_unavailable,
            ),
            ok = state.integrityVerified,
        )
    }
}

@Composable
private fun DetailLine(label: String, value: String, ok: Boolean) {
    StatusLine(
        label = label,
        detail = value,
        color = if (ok) SciFiColors.GoGreen else colorScheme.onSurfaceVariant,
    )
}

@Composable
private fun appRestrictionLine(state: ProtectionUiState): String = when {
    state.enforcementMismatch -> stringResource(R.string.enforcement_mismatch)
    state.isRecovering -> stringResource(R.string.state_applying)
    state.enforcementStatus == EnforcementStatus.ACTIVE_RESTRICTED -> stringResource(R.string.state_active)
    state.enforcementStatus == EnforcementStatus.NORMAL -> stringResource(R.string.state_active)
    else -> stringResource(R.string.enforcement_pending)
}

@Composable
private fun networkLine(state: ProtectionUiState): String = when (state.shieldStatus) {
    ShieldStatus.VERIFIED -> stringResource(R.string.state_active)
    ShieldStatus.RUNNING_UNVERIFIED -> stringResource(R.string.state_unverified)
    ShieldStatus.INITIALIZING, ShieldStatus.UNKNOWN -> stringResource(R.string.state_applying)
    ShieldStatus.PERMISSION_MISSING -> stringResource(R.string.state_permission_needed)
    ShieldStatus.DISCONNECTED -> stringResource(R.string.state_disconnected)
    ShieldStatus.FILTER_FAILED -> stringResource(R.string.state_not_working)
    ShieldStatus.DISABLED -> stringResource(R.string.state_unavailable)
}
