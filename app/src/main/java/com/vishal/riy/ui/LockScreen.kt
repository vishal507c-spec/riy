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
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
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
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.vishal.riy.R
import com.vishal.riy.lock.LockEngine
import com.vishal.riy.protection.enforcement.EnforcementStatus
import com.vishal.riy.protection.ui.ProtectionUiState

/**
 * The lock screen, shown while the backend requires the device to be under a
 * restriction (RESTRICTED / HARDENED), while it is restoring one (RECOVERY), or
 * while an enforcement mismatch is being repaired. It is deliberately
 * full-screen and opaque, offers NO button, Close affordance or bypass, and
 * consumes the system Back gesture (see [RiyApp]).
 *
 * ZERO CONFUSION. Every label is human language; the backend state names never
 * appear. One large status icon, one large title, one short explanation, one
 * primary status card, and the countdown â€” nothing else competes for attention.
 *
 * ONE COUNTDOWN ONLY. RESTRICTED and HARDENED are two *policies* over the SAME
 * deadline, so this screen formats exactly one [LockEngine] value for both and
 * there is no second timer anywhere. The screen never computes an expiry and
 * never starts a timer; it only formats the remaining time it is handed.
 *
 * HONESTY. A restriction the platform has not verified is shown as "Protection
 * Needs Attention" and is never labelled as protected, and a mismatch is never
 * accompanied by any "Continue anyway" affordance.
 */
@Composable
fun LockScreen(
    state: ProtectionUiState,
    modifier: Modifier = Modifier,
) {
    val presentation = state.userFacing()
    val alertColor = if (state.enforcementMismatch) SciFiColors.AlertRed else SciFiColors.Plasma

    SciFiFrame(
        header = "R.I.Y. // LOCKDOWN PROTOCOL",
        headerColor = alertColor,
        modifier = modifier.background(colorScheme.background),
    ) {
        Box(
            modifier = Modifier.fillMaxSize(),
            contentAlignment = Alignment.Center,
        ) {
        Column(
            modifier = Modifier
                .padding(28.dp)
                .verticalScroll(rememberScrollState()),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            StatusIcon(presentation.icon, alertColor)

            Spacer(Modifier.height(12.dp))

            // Cockpit scanner dressing; the countdown below stays the one deadline.
            ScanSweep(color = alertColor)

            // 1. One large title.
            Text(
                text = stringResource(presentation.titleRes),
                style = typography.headlineMedium,
                fontWeight = FontWeight.Bold,
                textAlign = TextAlign.Center,
            )

            // 2. One short explanation.
            Text(
                text = stringResource(presentation.detailRes),
                style = typography.bodyLarge,
                color = colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )

            // 3. The single countdown, only while a deadline is actually live.
            if (presentation.showsCountdown) {
                Spacer(Modifier.height(20.dp))
                Text(
                    text = LockEngine.formatRemainingBrief(state.remainingTime),
                    style = typography.displayLarge,
                    fontWeight = FontWeight.Bold,
                    color = colorScheme.primary,
                )
                Text(
                    text = stringResource(R.string.time_remaining_label),
                    style = typography.labelLarge,
                    color = colorScheme.onSurfaceVariant,
                )
            }

            Spacer(Modifier.height(20.dp))

            // 4. One primary status card.
            PrimaryStatusCard(state, presentation)

            // 5. Available apps while a restriction is live.
            if (state.isRestricted) {
                Spacer(Modifier.height(16.dp))
                AvailableAppsCard(state)
            }

            // 6. The optional explanation.
            Spacer(Modifier.height(16.dp))
            WhyIsThisHappening(state)

            // 7. Optional Protection Details, in normal language.
            Spacer(Modifier.height(16.dp))
            ProtectionDetailsCard(state)

            Spacer(Modifier.height(24.dp))
            Text(
                text = stringResource(R.string.app_version_label, com.vishal.riy.BuildConfig.VERSION_NAME),
                style = typography.bodySmall,
                color = colorScheme.onSurfaceVariant,
            )
        }
        }
    }
}

/**
 * The single large status icon. Coloured to match the situation, never to
 * reassure where the backend has not verified something.
 */
@Composable
private fun StatusIcon(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    glow: Color = SciFiColors.Plasma,
) {
    Box(
        modifier = Modifier
            .size(96.dp)
            .shadow(28.dp, CircleShape, ambientColor = glow)
            .background(colorScheme.primaryContainer, CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            modifier = Modifier.size(48.dp),
            tint = colorScheme.onPrimaryContainer,
        )
    }
}

/**
 * The one primary status card: "Protection Active" plus what that means right
 * now. The enforcement line inside it is the REAL platform read-back, so a
 * restriction that is not verified is said to be not verified.
 */
@Composable
private fun PrimaryStatusCard(
    state: ProtectionUiState,
    presentation: ProtectionStatusPresentation,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = if (state.enforcementMismatch) colorScheme.errorContainer
            else colorScheme.primaryContainer,
        ),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(22.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Box(
                    modifier = Modifier
                        .size(12.dp)
                        .background(dotColor(state), CircleShape),
                )
                Text(
                    text = stringResource(presentation.activeLineRes),
                    style = typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                    color = if (state.enforcementMismatch) colorScheme.onErrorContainer
                    else colorScheme.onPrimaryContainer,
                )
            }
            Text(
                text = stringResource(presentation.activeDetailRes),
                style = typography.bodyMedium,
                textAlign = TextAlign.Center,
                color = if (state.enforcementMismatch) colorScheme.onErrorContainer
                else colorScheme.onPrimaryContainer,
            )
            Spacer(Modifier.height(2.dp))
            Text(
                text = enforcementLine(state),
                style = typography.bodySmall,
                textAlign = TextAlign.Center,
                color = if (state.enforcementMismatch) colorScheme.onErrorContainer
                else colorScheme.onPrimaryContainer.copy(alpha = 0.85f),
            )
        }
    }
}

/**
 * The apps policy keeps available, by their friendly names. Package names are
 * never rendered: [friendlyLabel] substitutes a plain word whenever the
 * resolver could not read a real launcher label.
 */
@Composable
private fun AvailableAppsCard(state: ProtectionUiState) {
    val available = state.allowedApps
        .filter { it.category.isShownAsAvailable }
        .distinctBy { it.friendlyLabel() }

    if (available.isEmpty()) return

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = colorScheme.surfaceVariant),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(18.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(
                text = stringResource(R.string.available_now_label),
                style = typography.labelLarge,
                color = colorScheme.onSurfaceVariant,
            )
            available.forEach { app ->
                Text(
                    text = app.friendlyLabel(),
                    style = typography.titleMedium,
                    fontWeight = FontWeight.Medium,
                )
            }
        }
    }
}

/**
 * The optional, shame-free explanation. Tapped to expand; collapsed by default
 * so the screen stays calm. It explains the mechanism and the automatic end, and
 * nothing about what the user did.
 */
@Composable
private fun WhyIsThisHappening(state: ProtectionUiState) {
    var expanded by remember { mutableStateOf(false) }

    Text(
        text = stringResource(R.string.explanation_why),
        style = typography.bodyMedium,
        fontWeight = FontWeight.Medium,
        color = colorScheme.primary,
        textAlign = TextAlign.Center,
        modifier = Modifier
            .fillMaxWidth()
            .clickable {
                SciFiSound.blip()
                expanded = !expanded
            }
            .padding(vertical = 4.dp),
    )
    AnimatedVisibility(
        visible = expanded,
        enter = fadeIn() + expandVertically(),
    ) {
        Text(
            text = stringResource(R.string.explanation_restricted),
            style = typography.bodyMedium,
            color = colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 8.dp),
        )
    }
}
/** The honest enforcement verdict, in normal words. */
@Composable
private fun enforcementLine(state: ProtectionUiState): String = when {
    state.enforcementMismatch -> stringResource(R.string.enforcement_mismatch)
    state.isRecovering -> stringResource(R.string.enforcement_pending)
    state.enforcementStatus == EnforcementStatus.ACTIVE_RESTRICTED ->
        stringResource(R.string.enforcement_verified)
    state.enforcementStatus == EnforcementStatus.RECONCILING ->
        stringResource(R.string.enforcement_pending)
    else -> stringResource(R.string.enforcement_mismatch)
}

@Composable
private fun dotColor(state: ProtectionUiState): Color = when {
    state.enforcementMismatch -> colorScheme.error
    state.isRecovering -> SciFiColors.SolarAmber
    state.enforcementStatus == EnforcementStatus.ACTIVE_RESTRICTED -> SciFiColors.GoGreen
    state.enforcementStatus == EnforcementStatus.RECONCILING -> SciFiColors.SolarAmber
    else -> colorScheme.error
}
