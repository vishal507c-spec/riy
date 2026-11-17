package com.vishal.riy.ui

import android.app.Activity
import android.net.VpnService
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.vishal.riy.BuildConfig
import com.vishal.riy.R
import com.vishal.riy.blocker.ShieldStatus
import com.vishal.riy.protection.ui.ProtectionUiState

/**
 * RIY's single dashboard — redesigned as a calm, readable shield console.
 *
 * It renders the AUTHORITATIVE [ProtectionUiState]; it never decides a state,
 * never computes a deadline, and offers exactly one action: starting the VPN
 * after the system consent dialog. Every word and colour is a pure function of
 * the authoritative state, so the UI can never claim unverified protection.
 */
@Composable
fun ProtectionScreen(
    modifier: Modifier = Modifier,
    onOpenTracker: () -> Unit = {},
    viewModel: ProtectionViewModel = viewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current

    val vpnConsentLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            viewModel.enableProtection(context)
        }
    }

    ProtectionScreen(
        state = state,
        onEnableProtection = {
            SciFiSound.engage()
            val consentIntent = VpnService.prepare(context)
            if (consentIntent != null) vpnConsentLauncher.launch(consentIntent)
            else viewModel.enableProtection(context)
        },
        onOpenTracker = onOpenTracker,
        modifier = modifier,
    )
}

/** Stateless rendering of one [ProtectionUiState]. */
@Composable
internal fun ProtectionScreen(
    state: ProtectionUiState,
    onEnableProtection: () -> Unit,
    onOpenTracker: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val presentation = state.userFacing()
    val verified = state.shieldStatus == ShieldStatus.VERIFIED

    SciFiFrame(
        header = "RIY // SHIELD CONSOLE",
        headerColor = SciFiColors.NeonCyan,
        modifier = modifier,
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp, vertical = 16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Spacer(Modifier.height(18.dp))

            // Brand row — quiet, no technical vocabulary.
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(
                    modifier = Modifier
                        .size(34.dp)
                        .clip(CircleShape)
                        .background(SciFiColors.CyanDepth),
                    contentAlignment = Alignment.Center,
                ) {
                    Text("R", color = SciFiColors.NeonCyan, fontWeight = FontWeight.Bold)
                }
                Spacer(Modifier.width(10.dp))
                Column {
                    Text(
                        text = stringResource(R.string.app_name),
                        style = typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                    )
                    Text(
                        text = stringResource(R.string.shield_subtitle),
                        style = typography.labelSmall,
                        color = colorScheme.onSurfaceVariant,
                    )
                }
                Spacer(Modifier.weight(1f))
                Text(
                    text = BuildConfig.VERSION_NAME,
                    style = typography.labelSmall,
                    color = SciFiColors.Telemetry,
                )
            }

            // Hero: one icon, one title, one honest sentence.
            HeroStatusCard(presentation.icon, presentation.titleRes, presentation.detailRes, verified)

            // Primary status card.
            PrimaryStatusCard(state.shieldStatus)

            // The single action, only when nothing restricts the device.
            if (!state.isRestricted && !state.isRecovering) {
                Button(
                    onClick = onEnableProtection,
                    modifier = Modifier.fillMaxWidth().height(52.dp),
                    enabled = state.shieldStatus != ShieldStatus.INITIALIZING,
                    shape = RoundedCornerShape(14.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = SciFiColors.NeonCyan,
                        contentColor = SciFiColors.Void,
                    ),
                ) {
                    Text(
                        text = stringResource(R.string.action_enable_protection),
                        style = typography.titleSmall,
                        fontWeight = FontWeight.Bold,
                    )
                }
            }

            // Quick, truthful facts.
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                FactCard(
                    modifier = Modifier.weight(1f),
                    label = stringResource(R.string.details_device),
                    value = stringResource(
                        if (state.deviceOwnerActive) R.string.state_ready else R.string.state_unavailable,
                    ),
                    good = state.deviceOwnerActive,
                )
                FactCard(
                    modifier = Modifier.weight(1f),
                    label = stringResource(R.string.details_network),
                    value = stringResource(
                        if (verified) R.string.state_ready
                        else if (state.shieldStatus.isFiltering) R.string.state_unverified
                        else R.string.state_unavailable,
                    ),
                    good = verified,
                )
            }

            // Daily tracker entry.
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(onClick = onOpenTracker),
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(containerColor = colorScheme.surfaceVariant),
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = stringResource(R.string.tracker_entry_title),
                            style = typography.titleSmall,
                            fontWeight = FontWeight.Bold,
                        )
                        Text(
                            text = stringResource(R.string.tracker_entry_subtitle),
                            style = typography.bodySmall,
                            color = colorScheme.onSurfaceVariant,
                        )
                    }
                    Text("›", fontSize = 26.sp, color = SciFiColors.NeonCyan)
                }
            }

            ProtectionDetailsCard(state)
            ShieldLayersSection(state)
            HowItWorksSection()

            Spacer(Modifier.height(8.dp))
        }
    }
}

@Composable
private fun HeroStatusCard(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    titleRes: Int,
    detailRes: Int,
    verified: Boolean,
) {
    val transition = rememberInfiniteTransition(label = "hero")
    val pulse by transition.animateFloat(
        initialValue = 0.96f,
        targetValue = 1.06f,
        animationSpec = infiniteRepeatable(
            animation = tween(1800, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "pulse",
    )
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(22.dp),
        colors = CardDefaults.cardColors(containerColor = colorScheme.surfaceVariant),
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            val scale = if (verified) pulse else 1f
            Box(
                modifier = Modifier
                    .size(84.dp)
                    .clip(CircleShape)
                    .background(SciFiColors.CyanDepth.copy(alpha = if (verified) 0.9f else 0.55f)),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = if (verified) SciFiColors.GoGreen else colorScheme.onSurface,
                    modifier = Modifier.size((44 * scale).dp),
                )
            }
            Text(
                text = stringResource(titleRes),
                style = typography.headlineSmall,
                fontWeight = FontWeight.Bold,
                textAlign = TextAlign.Center,
            )
            Text(
                text = stringResource(detailRes),
                style = typography.bodyMedium,
                color = colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
        }
    }
}

@Composable
private fun FactCard(modifier: Modifier = Modifier, label: String, value: String, good: Boolean) {
    Card(
        modifier = modifier,
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = colorScheme.surfaceVariant),
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            Text(text = label, style = typography.labelSmall, color = colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(6.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier
                        .size(8.dp)
                        .clip(CircleShape)
                        .background(if (good) SciFiColors.GoGreen else SciFiColors.SolarAmber),
                )
                Spacer(Modifier.width(8.dp))
                Text(text = value, style = typography.titleSmall, fontWeight = FontWeight.SemiBold)
            }
        }
    }
}

/** One primary status card, driven only by the authoritative shield status. */
@Composable
private fun PrimaryStatusCard(status: ShieldStatus) {
    val broken = status == ShieldStatus.FILTER_FAILED || status == ShieldStatus.PERMISSION_MISSING
    val tint = when {
        broken -> colorScheme.error
        status.isFiltering -> SciFiColors.GoGreen
        status == ShieldStatus.INITIALIZING || status == ShieldStatus.DISCONNECTED -> SciFiColors.SolarAmber
        else -> SciFiColors.Telemetry
    }
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(containerColor = colorScheme.surface),
    ) {
        Row(modifier = Modifier.fillMaxWidth().padding(18.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier
                    .size(10.dp)
                    .clip(CircleShape)
                    .background(tint),
            )
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = stringResource(statusLabelRes(status)),
                    style = typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                )
                Text(
                    text = filterDetail(status),
                    style = typography.bodySmall,
                    color = colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

private fun statusLabelRes(status: ShieldStatus): Int = when (status) {
    ShieldStatus.VERIFIED -> R.string.filter_label_verified
    ShieldStatus.RUNNING_UNVERIFIED -> R.string.filter_label_unverified
    ShieldStatus.INITIALIZING, ShieldStatus.UNKNOWN -> R.string.filter_label_starting
    ShieldStatus.PERMISSION_MISSING -> R.string.filter_label_permission_missing
    ShieldStatus.DISCONNECTED -> R.string.filter_label_disconnected
    ShieldStatus.FILTER_FAILED -> R.string.filter_label_failed
    ShieldStatus.DISABLED -> R.string.filter_label_off
}

@Composable
private fun filterDetail(status: ShieldStatus): String = when (status) {
    ShieldStatus.VERIFIED -> stringResource(R.string.filter_verified)
    ShieldStatus.RUNNING_UNVERIFIED -> stringResource(R.string.filter_unverified)
    ShieldStatus.INITIALIZING, ShieldStatus.UNKNOWN -> stringResource(R.string.filter_initializing)
    ShieldStatus.PERMISSION_MISSING -> stringResource(R.string.filter_permission_missing)
    ShieldStatus.DISCONNECTED -> stringResource(R.string.filter_disconnected)
    ShieldStatus.FILTER_FAILED -> stringResource(R.string.filter_failed)
    ShieldStatus.DISABLED -> stringResource(R.string.filter_off)
}

