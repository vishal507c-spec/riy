package com.vishal.riy.ui

import android.content.Intent
import android.provider.Settings
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Chat
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme.colorScheme
import androidx.compose.material3.MaterialTheme.typography
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import com.vishal.riy.R

/**
 * The primary dashboard card: WhatsApp Status is BLOCKED.
 *
 * Clean Material 3 — rounded card, one icon per row, clear hierarchy, honest
 * status and no decorative animation. It states plainly what is protected
 * (WhatsApp) and what is stopped (Status), and it is the only place in the app
 * where a user-facing toggle exists for the guard.
 *
 * The toggle stores INTENT; the badge only says "Protection Active" when the
 * system has actually confirmed accessibility access, so the card can never
 * claim protection that is not running.
 */
@Composable
fun WhatsAppStatusCard(
    state: StatusBlockerUiState,
    onBlockingChanged: (Boolean) -> Unit,
    onEnableAccessibility: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val presentation = state.presentation()

    Card(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(24.dp),
        colors = CardDefaults.cardColors(containerColor = colorScheme.surface),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            // 1. Headline: the blocked surface, stated first and largest.
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                Box(
                    modifier = Modifier
                        .size(44.dp)
                        .background(colorScheme.errorContainer, RoundedCornerShape(14.dp)),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        imageVector = Icons.Filled.VisibilityOff,
                        contentDescription = null,
                        modifier = Modifier.size(22.dp),
                        tint = colorScheme.onErrorContainer,
                    )
                }
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = stringResource(presentation.titleRes),
                        style = typography.titleMedium,
                        color = colorScheme.onSurface,
                    )
                    Text(
                        text = stringResource(presentation.stateRes),
                        style = typography.headlineSmall,
                        fontWeight = FontWeight.Bold,
                        color = colorScheme.error,
                    )
                }
                if (presentation.blockingActive) {
                    StatusPill(
                        text = stringResource(presentation.activeBadgeRes),
                        color = SciFiColors.GoGreen,
                    )
                }
            }

            HorizontalDivider(color = colorScheme.outlineVariant)

            // 2. The single toggle. Its state is the user's stored intent.
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Box(
                    modifier = Modifier
                        .size(10.dp)
                        .background(
                            if (presentation.toggleEnabled) SciFiColors.GoGreen else colorScheme.outline,
                            CircleShape,
                        ),
                )
                Text(
                    text = stringResource(presentation.toggleStateRes),
                    style = typography.bodyLarge,
                    fontWeight = FontWeight.Medium,
                    color = colorScheme.onSurface,
                    modifier = Modifier.weight(1f),
                )
                Switch(
                    checked = presentation.toggleEnabled,
                    onCheckedChange = onBlockingChanged,
                    colors = SwitchDefaults.colors(
                        checkedThumbColor = colorScheme.onPrimary,
                        checkedTrackColor = colorScheme.primary,
                        uncheckedThumbColor = colorScheme.outline,
                        uncheckedTrackColor = colorScheme.surfaceVariant,
                        uncheckedBorderColor = colorScheme.outline,
                    ),
                )
            }

            HorizontalDivider(color = colorScheme.outlineVariant)

            // 3. What stays available — the reassurance the user actually needs.
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.Chat,
                    contentDescription = null,
                    modifier = Modifier.size(20.dp),
                    tint = colorScheme.onSurfaceVariant,
                )
                Text(
                    text = stringResource(presentation.appRes),
                    style = typography.bodyLarge,
                    color = colorScheme.onSurface,
                    modifier = Modifier.weight(1f),
                )
                Text(
                    text = stringResource(presentation.appStateRes),
                    style = typography.labelLarge,
                    color = colorScheme.onSurfaceVariant,
                )
            }

            // 4. One short explanation, and one honest privacy line.
            Text(
                text = stringResource(presentation.explanationRes),
                style = typography.bodyMedium,
                color = colorScheme.onSurfaceVariant,
            )
            Text(
                text = stringResource(R.string.status_blocker_privacy),
                style = typography.bodySmall,
                color = colorScheme.onSurfaceVariant,
            )

            // 5. Onboarding, only while accessibility access is missing.
            if (presentation.showOnboarding) {
                StatusOnboarding(
                    titleRes = presentation.onboardingTitleRes,
                    bodyRes = presentation.onboardingBodyRes,
                    buttonRes = presentation.enableButtonRes,
                    onEnableAccessibility = onEnableAccessibility,
                )
            }
        }
    }
}

/**
 * The permission explainer. It states exactly WHY accessibility access is
 * needed, links to the one system screen that grants it, and asks for nothing
 * else — no unrelated permission is ever requested.
 */
@Composable
private fun StatusOnboarding(
    titleRes: Int,
    bodyRes: Int,
    buttonRes: Int,
    onEnableAccessibility: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(colorScheme.surfaceVariant, RoundedCornerShape(18.dp))
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Icon(
                imageVector = Icons.Filled.Shield,
                contentDescription = null,
                modifier = Modifier.size(18.dp),
                tint = colorScheme.primary,
            )
            Text(
                text = stringResource(titleRes),
                style = typography.titleSmall,
                fontWeight = FontWeight.Bold,
                color = colorScheme.onSurface,
            )
        }
        Text(
            text = stringResource(bodyRes),
            style = typography.bodySmall,
            color = colorScheme.onSurfaceVariant,
        )
        Button(
            onClick = onEnableAccessibility,
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(14.dp),
            colors = ButtonDefaults.buttonColors(
                containerColor = colorScheme.primary,
                contentColor = colorScheme.onPrimary,
            ),
        ) {
            Text(
                text = stringResource(buttonRes),
                style = typography.labelLarge,
                fontWeight = FontWeight.SemiBold,
            )
        }
    }
}

/** Small rounded status label (e.g. "Protection Active"). */
@Composable
private fun StatusPill(text: String, color: Color) {
    Box(
        modifier = Modifier
            .background(color.copy(alpha = 0.16f), RoundedCornerShape(50))
            .padding(horizontal = 12.dp, vertical = 6.dp),
    ) {
        Text(
            text = text,
            style = typography.labelMedium,
            fontWeight = FontWeight.SemiBold,
            color = color,
        )
    }
}

/**
 * Reads the REAL store and the REAL system read-back, refreshing whenever the
 * screen resumes — which is exactly when the user comes back from Android's
 * accessibility settings after granting or revoking access.
 */
@Composable
private fun rememberStatusBlockerState(): Pair<StatusBlockerUiState, () -> Unit> {
    val context = LocalContext.current
    var state by remember { mutableStateOf(readStatusBlockerState(context)) }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
        state = readStatusBlockerState(context)
    }
    return state to { state = readStatusBlockerState(context) }
}

/**
 * Opens Android's accessibility settings on this app's own service entry. This
 * is the ONLY system screen the guard ever sends the user to, and it is the
 * only permission the feature requires.
 */
@Composable
fun rememberAccessibilitySettingsLauncher(): () -> Unit {
    val context = LocalContext.current
    return {
        runCatching {
            context.startActivity(
                Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
        }
    }
}

/** Convenience wrapper: the card plus its state hook, for the main screen. */
@Composable
fun WhatsAppStatusSection(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val (state, refresh) = rememberStatusBlockerState()
    val openAccessibilitySettings = rememberAccessibilitySettingsLauncher()

    WhatsAppStatusCard(
        state = state,
        onBlockingChanged = { enabled ->
            setStatusBlockingEnabled(context, enabled)
            // Re-read immediately so the card reflects what was really stored.
            refresh()
        },
        onEnableAccessibility = openAccessibilitySettings,
        modifier = modifier,
    )
}
