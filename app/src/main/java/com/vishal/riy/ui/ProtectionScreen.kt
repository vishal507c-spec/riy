package com.vishal.riy.ui

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.VpnService
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import com.vishal.riy.lock.LockEngine
import com.vishal.riy.protection.enforcement.EnforcementStatus
import com.vishal.riy.protection.policy.ProtectionState
import com.vishal.riy.protection.ui.ProtectionUiState

/**
 * The one and only screen of riy. It renders the AUTHORITATIVE
 * [ProtectionUiState] published by the protection backend — it never decides a
 * state, never computes a deadline, and offers exactly one action: enabling the
 * filtering VPN after the system consent dialog.
 *
 * The screen is deliberately stateless with respect to security: every label,
 * colour and countdown is a pure function of [state]. There is deliberately no
 * Disable, Pause, Bypass, "Continue Anyway" or Close control anywhere.
 */
@Composable
fun ProtectionScreen(
    modifier: Modifier = Modifier,
    viewModel: ProtectionViewModel = viewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val blockerSnapshot = BlockerState.current()

    // VPN consent (system dialog) — on approval the service actually starts.
    val vpnConsentLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            // Consent granted: hand the start request to the ViewModel. This is
            // the ONLY path that can begin filtering.
            viewModel.enableProtection(context)
        }
    }

    ProtectionScreen(
        state = state,
        blockerPhase = blockerSnapshot.phase,
        blockerFailureReason = blockerSnapshot.failureReason,
        // The single UI action. It goes through the system VPN consent dialog
        // first; consent is never assumed and never implied.
        onEnableProtection = {
            val consentIntent = VpnService.prepare(context)
            if (consentIntent != null) {
                vpnConsentLauncher.launch(consentIntent)
            } else {
                viewModel.enableProtection(context)
            }
        },
        modifier = modifier,
    )
}

/**
 * Stateless rendering of one [ProtectionUiState]. Kept free of any ViewModel or
 * Android framework write so the mapping "state → screen" can be reasoned about
 * (and asserted) on its own.
 */
@Composable
internal fun ProtectionScreen(
    state: ProtectionUiState,
    blockerPhase: BlockerState.Phase,
    blockerFailureReason: String?,
    onEnableProtection: () -> Unit,
    modifier: Modifier = Modifier,
) {
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

        Spacer(Modifier.height(8.dp))
        ModeHeader(state)
        ProtectionStatusCard(state, blockerPhase, blockerFailureReason)

        if (state.isRestricted) {
            Spacer(Modifier.height(8.dp))
            RestrictionDetails(state)
        }

        if (state.isRecovering || state.enforcementMismatch) {
            Spacer(Modifier.height(8.dp))
            RecoveryOrMismatchCard(state)
        }

        Spacer(Modifier.height(8.dp))
        IntegrityLine(state)

        // The single action the UI may offer, and only when nothing is
        // restricting the device. While RESTRICTED/HARDENED/RECOVERY is live
        // there is no affordance at all — protection cannot be turned off here.
        if (!state.isRestricted && !state.isRecovering) {
            Button(
                onClick = onEnableProtection,
                enabled = blockerPhase != BlockerState.Phase.CONNECTING,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(stringResource(R.string.action_enable_protection))
            }
        }

        Spacer(Modifier.height(8.dp))
        Text(
            text = stringResource(R.string.app_version_label, BuildConfig.VERSION_NAME),
            style = typography.bodySmall,
            color = colorScheme.onSurfaceVariant,
        )
    }
}

/**
 * The mode title, taken straight from the authoritative state. RESTRICTED and
 * HARDENED are never labelled as each other, and NORMAL is never labelled
 * "restricted".
 */
@Composable
private fun ModeHeader(state: ProtectionUiState) {
    val title = when {
        state.protectionState == ProtectionState.HARDENED ->
            stringResource(R.string.mode_hardened)
        state.isRestricted -> stringResource(R.string.mode_restricted)
        state.isRecovering -> stringResource(R.string.recovery_title)
        else -> stringResource(R.string.mode_normal)
    }
    Text(
        text = title,
        style = typography.titleLarge,
        fontWeight = FontWeight.SemiBold,
        color = if (state.isRestricted || state.isRecovering) colorScheme.primary
        else colorScheme.onBackground,
    )
}

@Composable
private fun ProtectionStatusCard(
    state: ProtectionUiState,
    phase: BlockerState.Phase,
    failureReason: String?,
) {
    val style = when {
        state.enforcementMismatch -> StatusStyle(
            stringResource(R.string.mismatch_title),
            colorScheme.errorContainer,
            colorScheme.onErrorContainer,
        )
        state.isRecovering -> StatusStyle(
            stringResource(R.string.recovery_title),
            colorScheme.tertiaryContainer,
            colorScheme.onTertiaryContainer,
        )
        state.protectionState == ProtectionState.HARDENED -> StatusStyle(
            stringResource(R.string.mode_hardened),
            colorScheme.errorContainer,
            colorScheme.onErrorContainer,
        )
        state.isRestricted -> StatusStyle(
            stringResource(R.string.mode_restricted),
            colorScheme.primaryContainer,
            colorScheme.onPrimaryContainer,
        )
        phase == BlockerState.Phase.CONNECTED -> StatusStyle(
            stringResource(R.string.status_protection_on),
            colorScheme.primaryContainer,
            colorScheme.onPrimaryContainer,
        )
        phase == BlockerState.Phase.CONNECTING -> StatusStyle(
            stringResource(R.string.status_connecting),
            colorScheme.tertiaryContainer,
            colorScheme.onTertiaryContainer,
        )
        phase == BlockerState.Phase.FAILED -> StatusStyle(
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
        colors = CardDefaults.cardColors(containerColor = style.container),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 28.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            StatusDot(state, phase)
            Spacer(Modifier.height(12.dp))
            Text(
                text = style.label,
                style = typography.headlineMedium,
                fontWeight = FontWeight.Bold,
                color = style.content,
            )
            Spacer(Modifier.height(6.dp))
            Text(
                text = statusDetail(state, phase, failureReason),
                style = typography.bodySmall,
                color = style.content,
            )
        }
    }
}

@Composable
private fun statusDetail(
    state: ProtectionUiState,
    phase: BlockerState.Phase,
    failureReason: String?,
): String = when {
    state.enforcementMismatch -> stringResource(R.string.mismatch_detail)
    state.isRecovering -> stringResource(R.string.recovery_detail)
    state.protectionState == ProtectionState.HARDENED ->
        stringResource(R.string.hardened_detail)
    state.isRestricted -> stringResource(R.string.restricted_detail)
    phase == BlockerState.Phase.CONNECTED -> stringResource(R.string.status_detail_active)
    phase == BlockerState.Phase.CONNECTING -> stringResource(R.string.status_detail_connecting)
    phase == BlockerState.Phase.FAILED ->
        failureReason ?: stringResource(R.string.status_detail_failed)
    else -> stringResource(R.string.status_detail_off)
}

/**
 * Restriction-specific facts: the countdown derived from the authoritative
 * deadline, and the allowed apps the resolver actually verified on this device.
 * No domain names or raw DNS history are shown.
 */
@Composable
private fun RestrictionDetails(state: ProtectionUiState) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = colorScheme.surfaceVariant),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(20.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                text = stringResource(R.string.time_remaining_label),
                style = typography.labelMedium,
                color = colorScheme.onSurfaceVariant,
            )
            // The deadline is LockEngine's. The UI only formats what it is given.
            Text(
                text = LockEngine.formatRemaining(state.remainingTime),
                style = typography.displayMedium,
                fontWeight = FontWeight.Bold,
            )
            if (state.allowedApps.isNotEmpty()) {
                Spacer(Modifier.height(4.dp))
                Text(
                    text = stringResource(R.string.allowed_apps_label),
                    style = typography.labelMedium,
                    color = colorScheme.onSurfaceVariant,
                )
                state.allowedApps.forEach { app ->
                    Text(
                        text = app.displayName,
                        style = typography.bodyMedium,
                    )
                }
            }
        }
    }
}

/**
 * Recovery and enforcement-mismatch are shown through the SAME honest card so
 * neither can be mistaken for a successfully-applied restriction. There is no
 * button in it — only the backend resolves either condition.
 */
@Composable
private fun RecoveryOrMismatchCard(state: ProtectionUiState) {
    val isMismatch = state.enforcementMismatch
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = if (isMismatch) colorScheme.errorContainer
            else colorScheme.tertiaryContainer,
        ),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(20.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text(
                text = if (isMismatch) stringResource(R.string.mismatch_title)
                else stringResource(R.string.recovery_title),
                style = typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = if (isMismatch) colorScheme.onErrorContainer
                else colorScheme.onTertiaryContainer,
            )
            Text(
                text = if (isMismatch) stringResource(R.string.mismatch_detail)
                else stringResource(R.string.recovery_detail),
                style = typography.bodySmall,
                color = if (isMismatch) colorScheme.onErrorContainer
                else colorScheme.onTertiaryContainer,
            )
        }
    }
}

/**
 * The integrity line reports the real Device Owner / uninstall-protection /
 * enforcement read-back — never a reassuring default. While unverified it says
 * so plainly instead of claiming protection is healthy.
 */
@Composable
private fun IntegrityLine(state: ProtectionUiState) {
    val enforcementText = when {
        state.enforcementMismatch -> stringResource(R.string.enforcement_mismatch)
        state.enforcementVerified -> stringResource(R.string.enforcement_verified)
        state.isRecovering -> stringResource(R.string.enforcement_pending)
        else -> stringResource(R.string.enforcement_mismatch)
    }
    val dotColor = when {
        state.enforcementMismatch -> colorScheme.error
        state.enforcementVerified -> Color(0xFF2E7D32)
        else -> Color(0xFFF9A825)
    }
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Box(
            modifier = Modifier
                .size(12.dp)
                .background(dotColor, CircleShape),
        )
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(
                text = enforcementText,
                style = typography.bodyMedium,
                fontWeight = FontWeight.Medium,
            )
            val integrityText = if (state.deviceOwnerActive && state.uninstallProtectionActive) {
                stringResource(R.string.integrity_owner_active) + " · " +
                    stringResource(R.string.integrity_uninstall_active)
            } else {
                stringResource(R.string.integrity_unavailable)
            }
            Text(
                text = integrityText,
                style = typography.bodySmall,
                color = colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun StatusDot(state: ProtectionUiState, phase: BlockerState.Phase) {
    val dotColor = when {
        state.enforcementMismatch -> colorScheme.error
        state.isRecovering -> Color(0xFFF9A825)
        state.protectionState == ProtectionState.HARDENED -> colorScheme.error
        state.isRestricted -> Color(0xFF2E7D32)
        phase == BlockerState.Phase.CONNECTED -> Color(0xFF2E7D32)
        phase == BlockerState.Phase.CONNECTING -> Color(0xFFF9A825)
        phase == BlockerState.Phase.FAILED -> colorScheme.error
        else -> Color(0xFF9E9E9E)
    }
    Box(
        modifier = Modifier
            .size(18.dp)
            .background(dotColor, CircleShape),
    )
}

private data class StatusStyle(val label: String, val container: Color, val content: Color)
