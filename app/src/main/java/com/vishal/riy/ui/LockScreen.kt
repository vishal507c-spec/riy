package com.vishal.riy.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme.colorScheme
import androidx.compose.material3.MaterialTheme.typography
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.vishal.riy.R
import com.vishal.riy.lock.LockEngine
import com.vishal.riy.protection.enforcement.EnforcementStatus
import com.vishal.riy.protection.ui.ProtectionUiState

/**
 * Full-screen, non-dismissible restriction screen. One authoritative deadline,
 * one honest status, no bypass affordance and no second timer.
 */
@Composable
fun LockScreen(
    state: ProtectionUiState,
    modifier: Modifier = Modifier,
) {
    val presentation = state.userFacing()
    val alert = if (state.enforcementMismatch) SciFiColors.AlertRed else SciFiColors.Plasma

    SciFiFrame(
        header = "RIY // DEVICE RESTRICTED",
        headerColor = alert,
        modifier = modifier.background(colorScheme.background),
    ) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = AppUI.ScreenPadding, vertical = 40.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                AppHeader(
                    title = stringResource(R.string.app_name),
                    subtitle = stringResource(R.string.shield_subtitle),
                )

                Box(
                    modifier = Modifier
                        .size(78.dp)
                        .background(alert.copy(alpha = 0.16f), CircleShape),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        imageVector = presentation.icon,
                        contentDescription = null,
                        tint = alert,
                        modifier = Modifier.size(40.dp),
                    )
                }

                Text(
                    text = stringResource(presentation.titleRes),
                    style = typography.headlineSmall,
                    fontWeight = FontWeight.Bold,
                    textAlign = TextAlign.Center,
                )
                Text(
                    text = stringResource(presentation.detailRes),
                    style = typography.bodyMedium,
                    color = colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                )

                if (presentation.showsCountdown) {
                    Spacer(Modifier.height(6.dp))
                    CountdownRing(
                        remainingMs = state.remainingTime,
                        startedAtMs = state.startedAt,
                        expiresAtMs = state.expiresAt,
                        ringColor = alert,
                    )
                    Text(
                        text = stringResource(R.string.time_remaining_label),
                        style = typography.labelLarge,
                        color = colorScheme.onSurfaceVariant,
                    )
                }

                Spacer(Modifier.height(6.dp))

                SectionCard(
                    container = if (state.enforcementMismatch) colorScheme.errorContainer
                    else colorScheme.primaryContainer,
                ) {
                    StatusLine(
                        label = stringResource(presentation.activeLineRes),
                        detail = stringResource(presentation.activeDetailRes),
                        color = if (state.enforcementMismatch) colorScheme.error else SciFiColors.GoGreen,
                    )
                    Spacer(Modifier.height(8.dp))
                    Text(
                        text = enforcementLine(state),
                        style = typography.bodySmall,
                        color = colorScheme.onSurfaceVariant,
                    )
                }

                if (state.isRestricted) {
                    val available = state.allowedApps
                        .filter { it.category.isShownAsAvailable }
                        .distinctBy { it.friendlyLabel() }
                    if (available.isNotEmpty()) {
                        SectionCard {
                            SectionLabel(stringResource(R.string.available_now_label))
                            Spacer(Modifier.height(8.dp))
                            available.forEach { app ->
                                Text(text = app.friendlyLabel(), style = typography.titleSmall)
                            }
                        }
                    }
                }

                var expanded by remember { mutableStateOf(false) }
                Text(
                    text = stringResource(R.string.explanation_why),
                    style = typography.bodyMedium,
                    color = colorScheme.primary,
                    modifier = Modifier
                        .clickable { SciFiSound.blip(); expanded = !expanded }
                        .padding(8.dp),
                )
                AnimatedVisibility(visible = expanded, enter = fadeIn() + expandVertically()) {
                    Text(
                        text = stringResource(R.string.explanation_restricted),
                        style = typography.bodySmall,
                        color = colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                    )
                }

                ProtectionDetailsCard(state)
            }
        }
    }
}

/** Pure rendering of the one authoritative deadline. */
@Composable
private fun CountdownRing(
    remainingMs: Long,
    startedAtMs: Long,
    expiresAtMs: Long,
    ringColor: Color,
) {
    val windowMs = (expiresAtMs - startedAtMs).coerceAtLeast(0L)
    val progress = if (windowMs > 0L) {
        (remainingMs.coerceIn(0L, windowMs).toFloat() / windowMs).coerceIn(0f, 1f)
    } else 0f

    Box(Modifier.size(200.dp), contentAlignment = Alignment.Center) {
        androidx.compose.foundation.Canvas(Modifier.size(200.dp)) {
            drawArc(
                color = ringColor.copy(alpha = 0.20f),
                startAngle = 0f,
                sweepAngle = 360f,
                useCenter = false,
                style = Stroke(width = 12f),
            )
            drawArc(
                color = ringColor,
                startAngle = -90f,
                sweepAngle = 360f * progress,
                useCenter = false,
                style = Stroke(width = 12f),
            )
        }
        Text(
            text = LockEngine.formatRemainingBrief(remainingMs),
            style = typography.displaySmall,
            fontWeight = FontWeight.Bold,
            color = colorScheme.primary,
            textAlign = TextAlign.Center,
        )
    }
}

@Composable
private fun enforcementLine(state: ProtectionUiState): String = when {
    state.enforcementMismatch -> stringResource(R.string.enforcement_mismatch)
    state.isRecovering -> stringResource(R.string.enforcement_pending)
    state.enforcementStatus == EnforcementStatus.ACTIVE_RESTRICTED -> stringResource(R.string.enforcement_verified)
    state.enforcementStatus == EnforcementStatus.RECONCILING -> stringResource(R.string.enforcement_pending)
    else -> stringResource(R.string.enforcement_mismatch)
}
