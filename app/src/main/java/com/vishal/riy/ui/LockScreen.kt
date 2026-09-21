package com.vishal.riy.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme.colorScheme
import androidx.compose.material3.MaterialTheme.typography
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.vishal.riy.R
import com.vishal.riy.lock.LockEngine
import com.vishal.riy.protection.policy.ProtectionState
import com.vishal.riy.protection.ui.ProtectionUiState

/**
 * The lock screen. Shown while a live restriction is active. It is deliberately
 * full-screen and opaque, offers NO button, Close affordance or bypass, and
 * consumes the system Back gesture (see [RiyApp]).
 *
 * Everything rendered here is a pure function of [state], which the backend
 * publishes through the protection state bridge. The countdown is the
 * AUTHORITATIVE LockEngine deadline — the screen never computes an expiry and
 * never starts a timer; it only formats the remaining time it is handed. There
 * is deliberately NO second timer for HARDENED: the same deadline governs both
 * RESTRICTED and HARDENED, exactly as the backend does.
 */
@Composable
fun LockScreen(
    state: ProtectionUiState,
    modifier: Modifier = Modifier,
) {
    val hardened = state.protectionState == ProtectionState.HARDENED

    Box(
        modifier = modifier
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
            Text(
                text = stringResource(R.string.lock_shield),
                style = typography.displayLarge,
            )
            Spacer(Modifier.height(8.dp))
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
            // The mode label: Restricted vs Hardened, straight from the state.
            Text(
                text = if (hardened) stringResource(R.string.mode_hardened)
                else stringResource(R.string.mode_restricted),
                style = typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                color = if (hardened) colorScheme.error else colorScheme.primary,
            )
            Spacer(Modifier.height(20.dp))
            // The one and only countdown: LockEngine's deadline, formatted.
            Text(
                text = LockEngine.formatRemaining(state.remainingTime),
                style = typography.displayMedium,
                fontWeight = FontWeight.Bold,
            )
            Spacer(Modifier.height(20.dp))
            Text(
                text = if (hardened) stringResource(R.string.hardened_detail)
                else stringResource(R.string.lock_detected),
                style = typography.bodyLarge,
                textAlign = TextAlign.Center,
            )
            Text(
                text = stringResource(R.string.lock_hint),
                style = typography.bodyMedium,
                color = colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
            // Honest enforcement status. A restriction that is not verified on
            // the platform is shown as such — never as "fully protected".
            val statusText = when {
                state.enforcementMismatch -> stringResource(R.string.enforcement_mismatch)
                state.isRecovering -> stringResource(R.string.enforcement_pending)
                state.enforcementVerified -> stringResource(R.string.enforcement_verified)
                else -> stringResource(R.string.enforcement_mismatch)
            }
            Spacer(Modifier.height(12.dp))
            Text(
                text = statusText,
                style = typography.bodySmall,
                color = if (state.enforcementMismatch) colorScheme.error
                else colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
        }
    }
}
