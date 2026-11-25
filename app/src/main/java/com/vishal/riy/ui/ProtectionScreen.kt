package com.vishal.riy.ui

import android.app.Activity
import android.net.VpnService
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
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
 * RIY's home page — deliberately simple.
 *
 * A non-technical person sees three things first: whether the shield is on, one
 * plain sentence about what that means, and one button if anything needs
 * attention. The technical checks are still available, but they live behind a
 * single "See every check" toggle so they can never crowd the main answer.
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
    val actionAllowed = !state.isRestricted && !state.isRecovering &&
        state.shieldStatus != ShieldStatus.VERIFIED
    val actionEnabled = state.shieldStatus != ShieldStatus.INITIALIZING &&
        state.shieldStatus != ShieldStatus.UNKNOWN

    SciFiFrame(
        header = "RIY",
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

            AppHeader(
                title = stringResource(R.string.shield_title),
                subtitle = stringResource(R.string.shield_subtitle),
                trailing = {
                    Text(
                        text = BuildConfig.VERSION_NAME,
                        style = typography.labelSmall,
                        color = SciFiColors.Telemetry,
                    )
                },
            )

            // One icon, one title, one honest sentence — the whole story.
            HeroStatusCard(presentation.icon, presentation.titleRes, presentation.detailRes, verified)

            if (actionAllowed) {
                Button(
                    onClick = onEnableProtection,
                    modifier = Modifier.fillMaxWidth().height(52.dp),
                    enabled = actionEnabled,
                    shape = RoundedCornerShape(14.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = SciFiColors.NeonCyan,
                        contentColor = SciFiColors.Void,
                    ),
                ) {
                    Text(
                        text = stringResource(protectionActionLabel(state.shieldStatus)),
                        style = typography.titleSmall,
                        fontWeight = FontWeight.Bold,
                    )
                }
            }

            // Two plain facts. No "network", "integrity" or "enforcement".
            SectionCard {
                SectionLabel(stringResource(R.string.shield_working_title))
                Spacer(Modifier.height(12.dp))
                FactRow(
                    label = stringResource(R.string.shield_sites_label),
                    value = stringResource(websiteValueRes(state.shieldStatus)),
                    color = if (verified) SciFiColors.GoGreen else SciFiColors.SolarAmber,
                )
            }

            // The daily tracker entry, in the same plain language.
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(onClick = onOpenTracker),
                shape = AppUI.CardRadius,
                colors = CardDefaults.cardColors(containerColor = colorScheme.surfaceVariant),
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(text = "🔥", style = typography.headlineSmall)
                    Spacer(Modifier.width(12.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = stringResource(R.string.tracker_entry_title),
                            style = typography.titleSmall,
                            fontWeight = FontWeight.Bold,
                        )
                        Text(
                            text = stringResource(R.string.tracker_entry_prompt),
                            style = typography.bodySmall,
                            color = colorScheme.onSurfaceVariant,
                        )
                    }
                    Text("›", fontSize = 26.sp, color = SciFiColors.NeonCyan)
                }
            }

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

/** One plain fact line: "Adult websites — Blocked". */
@Composable
private fun FactRow(label: String, value: String, color: Color) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(9.dp).background(color, CircleShape))
        Spacer(Modifier.width(12.dp))
        Text(
            text = label,
            style = typography.bodyMedium,
            color = colorScheme.onSurface,
            modifier = Modifier.weight(1f),
        )
        Text(
            text = value,
            style = typography.titleSmall,
            fontWeight = FontWeight.Bold,
            color = color,
        )
    }
}

/** The button says exactly what it will do, in the user's words. */
private fun protectionActionLabel(status: ShieldStatus): Int = when (status) {
    ShieldStatus.PERMISSION_MISSING -> R.string.action_allow_protection
    ShieldStatus.FILTER_FAILED,
    ShieldStatus.DISCONNECTED,
    ShieldStatus.RUNNING_UNVERIFIED -> R.string.action_fix_protection
    ShieldStatus.INITIALIZING,
    ShieldStatus.UNKNOWN -> R.string.action_protection_starting
    else -> R.string.action_enable_protection
}

/** Plain words for the one thing on this page that is easy to misunderstand. */
private fun websiteValueRes(status: ShieldStatus): Int = when (status) {
    ShieldStatus.VERIFIED -> R.string.shield_sites_blocked
    ShieldStatus.RUNNING_UNVERIFIED -> R.string.shield_sites_not_confirmed
    ShieldStatus.INITIALIZING, ShieldStatus.UNKNOWN -> R.string.shield_sites_starting
    ShieldStatus.DISABLED -> R.string.shield_sites_off
    ShieldStatus.PERMISSION_MISSING,
    ShieldStatus.DISCONNECTED,
    ShieldStatus.FILTER_FAILED -> R.string.shield_sites_not_blocked
}
