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
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
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
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * RIY Shield home — four truthful states, one actionable button, no duplicate
 * status cards. PROTECTED is reachable only after a passing self-test; a
 * running-but-unproven tunnel is NOT VERIFIED, never green.
 */
@Composable
fun ProtectionScreen(
    modifier: Modifier = Modifier,
    onOpenTracker: () -> Unit = {},
    viewModel: ProtectionViewModel = viewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val isBusy by viewModel.isBusy.collectAsStateWithLifecycle()
    val context = LocalContext.current

    val vpnConsentLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            viewModel.fixProtection(context)
        } else {
            // Denied consent must be visible, never a dead button.
            viewModel.reportPermissionDenied()
        }
    }

    ProtectionScreen(
        state = state,
        isBusy = isBusy,
        onFixProtection = {
            // Immediate audible/visual acknowledgement, then real work.
            SciFiSound.engage()
            val consent = VpnService.prepare(context)
            if (consent != null) vpnConsentLauncher.launch(consent)
            else viewModel.fixProtection(context)
        },
        onOpenTracker = onOpenTracker,
        modifier = modifier,
    )
}

/** Stateless rendering of one [ProtectionUiState]. */
@Composable
internal fun ProtectionScreen(
    state: ProtectionUiState,
    isBusy: Boolean = false,
    onFixProtection: () -> Unit,
    onOpenTracker: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val display = shieldDisplayOf(state.shieldStatus, isBusy)
    val icon = state.userFacing().icon
    val color = when (display) {
        ShieldDisplayState.PROTECTED -> SciFiColors.GoGreen
        ShieldDisplayState.CHECKING -> SciFiColors.NeonCyan
        ShieldDisplayState.NOT_VERIFIED -> SciFiColors.SolarAmber
        ShieldDisplayState.NOT_PROTECTED -> colorScheme.error
    }
    val titleRes = when (display) {
        ShieldDisplayState.PROTECTED -> R.string.shield_state_protected
        ShieldDisplayState.CHECKING -> R.string.shield_state_checking
        ShieldDisplayState.NOT_VERIFIED -> R.string.shield_state_not_verified
        ShieldDisplayState.NOT_PROTECTED -> R.string.shield_state_not_protected
    }
    val detailRes = when (display) {
        ShieldDisplayState.PROTECTED -> R.string.shield_detail_protected
        ShieldDisplayState.CHECKING -> R.string.recovery_step_verifying
        ShieldDisplayState.NOT_VERIFIED -> R.string.shield_detail_not_verified
        ShieldDisplayState.NOT_PROTECTED -> R.string.shield_detail_not_protected
    }
    val actionLabelRes = when {
        display == ShieldDisplayState.CHECKING -> R.string.recovery_step_checking
        state.shieldStatus == ShieldStatus.PERMISSION_MISSING -> R.string.action_allow_protection
        display == ShieldDisplayState.NOT_VERIFIED -> R.string.action_fix_protection
        else -> R.string.action_enable_protection
    }
    val actionAllowed = !state.isRestricted && !state.isRecovering &&
        display != ShieldDisplayState.PROTECTED

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

            StatusHero(
                icon = icon,
                title = stringResource(titleRes),
                detail = stringResource(detailRes),
                reason = state.shieldDetail?.takeIf { it.isNotBlank() },
                lastVerifiedAt = state.lastVerifiedAt,
                color = color,
                highlight = display == ShieldDisplayState.PROTECTED,
            )

            // Exactly one button, and only when something needs attention.
            if (actionAllowed) {
                Button(
                    onClick = onFixProtection,
                    modifier = Modifier.fillMaxWidth().height(52.dp),
                    enabled = display != ShieldDisplayState.CHECKING,
                    shape = RoundedCornerShape(14.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = SciFiColors.NeonCyan,
                        contentColor = SciFiColors.Void,
                    ),
                ) {
                    Text(
                        text = stringResource(actionLabelRes),
                        style = typography.titleSmall,
                        fontWeight = FontWeight.Bold,
                    )
                }
            }

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
private fun StatusHero(
    icon: ImageVector,
    title: String,
    detail: String,
    reason: String?,
    lastVerifiedAt: Long?,
    color: Color,
    highlight: Boolean,
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
            val scale = if (highlight) pulse else 1f
            Box(
                modifier = Modifier
                    .size(84.dp)
                    .clip(CircleShape)
                    .background(SciFiColors.CyanDepth.copy(alpha = if (highlight) 0.9f else 0.55f)),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = if (highlight) color else colorScheme.onSurface,
                    modifier = Modifier.size((44 * scale).dp),
                )
            }
            Text(
                text = title,
                style = typography.headlineSmall,
                fontWeight = FontWeight.Bold,
                color = color,
                textAlign = TextAlign.Center,
            )
            Text(
                text = detail,
                style = typography.bodyMedium,
                color = colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
            if (reason != null) {
                Text(
                    text = reason,
                    style = typography.bodySmall,
                    color = color,
                    textAlign = TextAlign.Center,
                )
            }
            LastVerifiedLine(lastVerifiedAt)
        }
    }
}

/** The last PASSING verification, never a claim about the current state. */
@Composable
private fun LastVerifiedLine(timestamp: Long?) {
    if (timestamp == null) return
    val formatted = remember(timestamp) {
        runCatching {
            SimpleDateFormat("dd MMM, HH:mm", Locale.getDefault()).format(Date(timestamp))
        }.getOrNull()
    } ?: return
    Text(
        text = stringResource(R.string.shield_last_verified, formatted),
        style = typography.labelSmall,
        color = colorScheme.onSurfaceVariant,
        textAlign = TextAlign.Center,
    )
}
