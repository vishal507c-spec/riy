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
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme.colorScheme
import androidx.compose.material3.MaterialTheme.typography
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.vishal.riy.BuildConfig
import com.vishal.riy.R
import com.vishal.riy.blocker.BlockerState
import com.vishal.riy.protection.ui.ProtectionUiState

/**
 * The one and only screen of RIY when no restriction is live. It renders the
 * AUTHORITATIVE [ProtectionUiState] the backend publishes â€” it never decides a
 * state, never computes a deadline, and offers exactly one action: enabling the
 * filtering VPN after the system consent dialog.
 *
 * ZERO CONFUSION. The screen is deliberately minimal: one large status icon, one
 * large title, one short explanation, one primary status card, and an optional
 * details section. No risk score, no technical state, no event counts, no
 * package names, no logs and no engine names are ever shown. The internal state
 * vocabulary of the protection backend appears nowhere in the UI.
 *
 * The screen is stateless with respect to security: every label, colour and
 * countdown is a pure function of [state]. There is deliberately no Disable,
 * Pause, Bypass, "Continue Anyway" or Close control anywhere.
 */
@Composable
fun ProtectionScreen(
    modifier: Modifier = Modifier,
    viewModel: ProtectionViewModel = viewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val blockerSnapshot = BlockerState.current()

    // VPN consent (system dialog) â€” on approval the service actually starts.
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
        // The single UI action. It goes through the system VPN consent dialog
        // first; consent is never assumed and never implied.
        onEnableProtection = {
            // Shield power-up sweep, then the normal consent/start path.
            SciFiSound.engage()
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
 * Stateless rendering of one [ProtectionUiState], kept free of any ViewModel so
 * the mapping "state â†’ screen" can be reasoned about and asserted on its own.
 */
@Composable
internal fun ProtectionScreen(
    state: ProtectionUiState,
    blockerPhase: BlockerState.Phase,
    onEnableProtection: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val presentation = state.userFacing()

    SciFiFrame(
        header = "R.I.Y. // PLANETARY SHIELD",
        headerColor = SciFiColors.NeonCyan,
        modifier = modifier,
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
        Spacer(Modifier.height(28.dp))

        // Scanner sweep under the header — pure cockpit dressing.
        ScanSweep(color = SciFiColors.NeonCyan)

        // 1. One large status icon, glowing like a holo projector.
        Box(
            modifier = Modifier
                .size(96.dp)
                .shadow(28.dp, CircleShape, ambientColor = SciFiColors.NeonCyan)
                .background(colorScheme.primaryContainer, CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = presentation.icon,
                contentDescription = null,
                modifier = Modifier.size(48.dp),
                tint = colorScheme.onPrimaryContainer,
            )
        }

        // 2. One large title.
        Text(
            text = stringResource(presentation.titleRes),
            style = typography.headlineMedium,
            fontWeight = FontWeight.Bold,
            textAlign = TextAlign.Center,
        )

        // 3. One short explanation.
        Text(
            text = stringResource(presentation.detailRes),
            style = typography.bodyLarge,
            color = colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )

        Spacer(Modifier.height(8.dp))

        // 4. One primary status card.
        PrimaryStatusCard(state, blockerPhase)

        // 5. Optional Protection Details, in normal language.
        ProtectionDetailsCard(state)

        // The single action the UI may offer, and only when nothing is
        // restricting the device. While a restriction or a restoration is live
        // there is no affordance at all â€” protection cannot be turned off here.
        if (!state.isRestricted && !state.isRecovering) {
            Spacer(Modifier.height(8.dp))
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
}

/**
 * The one primary status card. While nothing is restricted it states plainly
 * whether the filter is running; the text always comes from the REAL service
 * state, so the card can never claim protection that is not there.
 */
@Composable
private fun PrimaryStatusCard(
    state: ProtectionUiState,
    phase: BlockerState.Phase,
) {
    val container = when {
        phase == BlockerState.Phase.FAILED -> colorScheme.errorContainer
        phase == BlockerState.Phase.CONNECTED -> colorScheme.primaryContainer
        phase == BlockerState.Phase.CONNECTING -> colorScheme.tertiaryContainer
        else -> colorScheme.surfaceVariant
    }
    val content = when {
        phase == BlockerState.Phase.FAILED -> colorScheme.onErrorContainer
        phase == BlockerState.Phase.CONNECTED -> colorScheme.onPrimaryContainer
        phase == BlockerState.Phase.CONNECTING -> colorScheme.onTertiaryContainer
        else -> colorScheme.onSurfaceVariant
    }

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = container),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Box(
                    modifier = Modifier
                        .size(12.dp)
                        .background(dotColor(phase), CircleShape),
                )
                Text(
                    text = stringResource(R.string.protection_active_line),
                    style = typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                    color = content,
                )
            }
            Text(
                text = filterDetail(phase),
                style = typography.bodyMedium,
                textAlign = TextAlign.Center,
                color = content,
            )
        }
    }
}

@Composable
private fun filterDetail(phase: BlockerState.Phase): String = when (phase) {
    BlockerState.Phase.CONNECTED -> stringResource(R.string.filter_on)
    BlockerState.Phase.CONNECTING -> stringResource(R.string.filter_connecting)
    BlockerState.Phase.FAILED -> stringResource(R.string.filter_failed)
    BlockerState.Phase.OFF -> stringResource(R.string.filter_off)
}

@Composable
private fun dotColor(phase: BlockerState.Phase): Color = when (phase) {
    BlockerState.Phase.CONNECTED -> SciFiColors.GoGreen
    BlockerState.Phase.CONNECTING -> SciFiColors.SolarAmber
    BlockerState.Phase.FAILED -> colorScheme.error
    BlockerState.Phase.OFF -> Color(0xFF5A6B7A)
}

