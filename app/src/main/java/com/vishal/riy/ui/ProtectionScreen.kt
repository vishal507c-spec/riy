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
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
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
import com.vishal.riy.blocker.BlockerStateStore
import com.vishal.riy.blocker.ShieldStatus
import com.vishal.riy.blocker.shieldStatusOf
import com.vishal.riy.protection.ui.ProtectionUiState

/**
 * The one and only screen of RIY when no restriction is live. It renders the
 * AUTHORITATIVE [ProtectionUiState] the backend publishes â€” it never decides a
 * state, never computes a deadline, and offers exactly one action: enabling the
 * filtering VPN after the system consent dialog.
 *
 * ZERO CONFUSION. The screen shows one large status icon, one large title, one
 * short explanation, one primary status card, an optional details section,
 * and the feature showcase (live guard layers + the four-step pipeline).
 * No risk score, no technical state, no event counts, no
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
    onOpenTracker: () -> Unit = {},
    viewModel: ProtectionViewModel = viewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val blockerSnapshot = BlockerState.current()
    // The user's persisted ON/OFF choice is what separates "switched off" from
    // "switched on but broken"; the phase alone cannot tell them apart.
    val protectionWanted = remember(blockerSnapshot.phase) {
        BlockerStateStore(context).isProtectionWanted()
    }
    val blockerStatus = shieldStatusOf(blockerSnapshot, protectionWanted)

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
        blockerStatus = blockerStatus,
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
        onOpenTracker = onOpenTracker,
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
    blockerStatus: ShieldStatus,
    onEnableProtection: () -> Unit,
    onOpenTracker: () -> Unit,
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

        // 1. The shield orb: a large glowing status icon that breathes while
        //    the filter genuinely runs, and sits still otherwise.
        ShieldOrb(
            icon = presentation.icon,
            glow = SciFiColors.NeonCyan,
            breathing = blockerStatus == ShieldStatus.VERIFIED,
        )

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
PrimaryStatusCard(state, blockerStatus)

        // 4a. The Daily Tracker, placed directly under the status so it is
        //     reachable immediately instead of being buried further down.
        TrackerEntryRow(onOpen = onOpenTracker)

        // 4b. The WhatsApp Status guard — the app's primary feature card.
        //     Rendered straight after the primary status so it reads as the
        //     headline capability, without disturbing anything below it.
        WhatsAppStatusSection()

        // 5. Optional Protection Details, in normal language.
        ProtectionDetailsCard(state)

        // 6. What the app guards (live layer statuses) and how it works
        //    (static four-step pipeline). Both render only — no controls,
        //    no state decisions, and the backend vocabulary appears nowhere.
        ShieldLayersSection(state)
        HowItWorksSection()

        // The single action the UI may offer, and only when nothing is
        // restricting the device. While a restriction or a restoration is live
        // there is no affordance at all â€” protection cannot be turned off here.
        if (!state.isRestricted && !state.isRecovering) {
            Spacer(Modifier.height(8.dp))
            Button(
onClick = onEnableProtection,
                // Disabled ONLY while the tunnel is genuinely mid-handshake.
                // It must stay enabled when protection is disconnected, failed
                // or permission-blocked — those are precisely the states in which
                // the user needs to press it. Disabling it there made recovery
                // impossible.
                enabled = blockerStatus != ShieldStatus.INITIALIZING,
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
    status: ShieldStatus,
) {
    val broken = status == ShieldStatus.FILTER_FAILED || status == ShieldStatus.PERMISSION_MISSING
    val filtering = status.isFiltering
    val container = when {
        broken -> colorScheme.errorContainer
        filtering -> colorScheme.primaryContainer
        status == ShieldStatus.INITIALIZING || status == ShieldStatus.DISCONNECTED ->
            colorScheme.tertiaryContainer
        else -> colorScheme.surfaceVariant
    }
    val content = when {
        broken -> colorScheme.onErrorContainer
        filtering -> colorScheme.onPrimaryContainer
        status == ShieldStatus.INITIALIZING || status == ShieldStatus.DISCONNECTED ->
            colorScheme.onTertiaryContainer
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
                        .background(dotColor(status), CircleShape),
                )
                Text(
                    text = stringResource(statusLabelRes(status)),
                    style = typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                    color = content,
                )
            }
            Text(
                text = filterDetail(status),
                style = typography.bodyMedium,
                textAlign = TextAlign.Center,
                color = content,
            )
        }
    }
}

/**
 * The card's headline. It must never read "Protection Active" for a state that
 * is not protecting the device, which is why every unverified/broken value gets
 * its own label instead of inheriting a generic green one.
 */
private fun statusLabelRes(status: ShieldStatus): Int = when (status) {
    ShieldStatus.VERIFIED -> R.string.filter_label_verified
    ShieldStatus.RUNNING_UNVERIFIED -> R.string.filter_label_unverified
    ShieldStatus.INITIALIZING -> R.string.filter_label_starting
    ShieldStatus.UNKNOWN -> R.string.filter_label_starting
    ShieldStatus.PERMISSION_MISSING -> R.string.filter_label_permission_missing
    ShieldStatus.DISCONNECTED -> R.string.filter_label_disconnected
    ShieldStatus.FILTER_FAILED -> R.string.filter_label_failed
    ShieldStatus.DISABLED -> R.string.filter_label_off
}

@Composable
private fun filterDetail(status: ShieldStatus): String = when (status) {
    ShieldStatus.VERIFIED -> stringResource(R.string.filter_verified)
    ShieldStatus.RUNNING_UNVERIFIED -> stringResource(R.string.filter_unverified)
    ShieldStatus.INITIALIZING -> stringResource(R.string.filter_initializing)
    ShieldStatus.UNKNOWN -> stringResource(R.string.filter_initializing)
    ShieldStatus.PERMISSION_MISSING -> stringResource(R.string.filter_permission_missing)
    ShieldStatus.DISCONNECTED -> stringResource(R.string.filter_disconnected)
    ShieldStatus.FILTER_FAILED -> stringResource(R.string.filter_failed)
    ShieldStatus.DISABLED -> stringResource(R.string.filter_off)
}

@Composable
private fun dotColor(status: ShieldStatus): Color = when (status) {
    ShieldStatus.VERIFIED -> SciFiColors.GoGreen
    ShieldStatus.RUNNING_UNVERIFIED -> SciFiColors.SolarAmber
    ShieldStatus.INITIALIZING,
    ShieldStatus.DISCONNECTED,
    -> SciFiColors.SolarAmber

    ShieldStatus.FILTER_FAILED,
    ShieldStatus.PERMISSION_MISSING,
    -> colorScheme.error

    ShieldStatus.DISABLED -> Color(0xFF5A6B7A)
    ShieldStatus.UNKNOWN -> Color(0xFF5A6B7A)
}

