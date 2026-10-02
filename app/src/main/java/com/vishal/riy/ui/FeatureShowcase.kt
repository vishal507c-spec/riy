package com.vishal.riy.ui

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
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Block
import androidx.compose.material.icons.filled.Chat
import androidx.compose.material.icons.filled.Dns
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material.icons.filled.Timer
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
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.vishal.riy.R
import com.vishal.riy.protection.enforcement.AllowedAppCategory
import com.vishal.riy.protection.ui.ProtectionUiState

/**
 * The advanced feature showcase: WHAT the app guards and HOW it works,
 * rendered as "Shield Layers" plus a four-step pipeline.
 *
 * PURE MAPPING + DUMB RENDERING. [shieldLayers] and [howItWorksSteps] are pure
 * functions of the authoritative [ProtectionUiState] (or of nothing, for the
 * static pipeline) — they hold no state, compute no deadline and reach no
 * backend. The composables below only render what those functions return.
 *
 * ZERO CONFUSION (same contract as the rest of the UI):
 *  - every title/description is plain human language from string resources;
 *  - no package name, domain, log line, event count, score or engine name;
 *  - a layer is ACTIVE only off a REAL backend read, READY when its guard is
 *    armed but idle, UNAVAILABLE otherwise — never a reassuring default;
 *  - no Disable / Pause / Bypass / Close control exists anywhere here.
 */
enum class ShieldLayerId {
    NETWORK,
    LOCK,
    TELEGRAM,
    BYPASS,
    INSTALL,
    RECOVERY,
}

/** What the layer's guard is doing right now. */
enum class ShieldLayerStatus {
    ACTIVE,
    READY,
    UNAVAILABLE,
}

/**
 * One guard layer: stable identity, human-language texts (plus a second-depth
 * line revealed on tap), and its live status.
 * Pure Kotlin (no Compose) so the mapping stays JVM-unit-testable.
 */
data class ShieldLayer(
    val id: ShieldLayerId,
    val titleRes: Int,
    val descRes: Int,
    val moreRes: Int,
    val status: ShieldLayerStatus,
)

/**
 * Maps the authoritative UI state onto the six guard layers. Every ACTIVE is
 * earned by a real backend read:
 *
 *  - NETWORK  : the filtering VPN is genuinely connected.
 *  - LOCK     : a restriction deadline is live; READY while the filter runs.
 *  - TELEGRAM : usable now (no restriction), or confirmed in the live
 *    allowlist; READY when policy allows it but the app is not on the device.
 *  - BYPASS / INSTALL : enforced now (live verified restriction), or armed
 *    while the filter runs behind Device Owner; READY on Device Owner alone.
 *  - RECOVERY : the last integrity pass verified everything.
 */
fun shieldLayers(state: ProtectionUiState): List<ShieldLayer> {
    val telegramConfirmed = !state.isRestricted ||
        state.allowedApps.any { it.category == AllowedAppCategory.COMMUNICATION }
    val enforcedNow = state.isRestricted && state.enforcementVerified
    val armedWhileFiltering = state.protectionActive && state.deviceOwnerActive

    fun guardStatus(enforced: Boolean): ShieldLayerStatus = when {
        enforced -> ShieldLayerStatus.ACTIVE
        state.deviceOwnerActive -> ShieldLayerStatus.READY
        else -> ShieldLayerStatus.UNAVAILABLE
    }

    return listOf(
        ShieldLayer(
            id = ShieldLayerId.NETWORK,
            titleRes = R.string.layer_network_title,
            descRes = R.string.layer_network_desc,
            moreRes = R.string.layer_network_more,
            status = if (state.protectionActive) ShieldLayerStatus.ACTIVE
            else ShieldLayerStatus.UNAVAILABLE,
        ),
        ShieldLayer(
            id = ShieldLayerId.LOCK,
            titleRes = R.string.layer_lock_title,
            descRes = R.string.layer_lock_desc,
            moreRes = R.string.layer_lock_more,
            status = when {
                state.isRestricted -> ShieldLayerStatus.ACTIVE
                state.protectionActive -> ShieldLayerStatus.READY
                else -> ShieldLayerStatus.UNAVAILABLE
            },
        ),
        ShieldLayer(
            id = ShieldLayerId.TELEGRAM,
            titleRes = R.string.layer_telegram_title,
            descRes = R.string.layer_telegram_desc,
            moreRes = R.string.layer_telegram_more,
            status = if (telegramConfirmed) ShieldLayerStatus.ACTIVE
            else ShieldLayerStatus.READY,
        ),
        ShieldLayer(
            id = ShieldLayerId.BYPASS,
            titleRes = R.string.layer_bypass_title,
            descRes = R.string.layer_bypass_desc,
            moreRes = R.string.layer_bypass_more,
            status = guardStatus(enforcedNow || armedWhileFiltering),
        ),
        ShieldLayer(
            id = ShieldLayerId.INSTALL,
            titleRes = R.string.layer_install_title,
            descRes = R.string.layer_install_desc,
            moreRes = R.string.layer_install_more,
            status = guardStatus(enforcedNow || armedWhileFiltering),
        ),
        ShieldLayer(
            id = ShieldLayerId.RECOVERY,
            titleRes = R.string.layer_recovery_title,
            descRes = R.string.layer_recovery_desc,
            moreRes = R.string.layer_recovery_more,
            status = if (state.integrityVerified) ShieldLayerStatus.ACTIVE
            else ShieldLayerStatus.UNAVAILABLE,
        ),
    )
}

/** One static pipeline step: its number and its human-language texts. */
data class HowStep(
    val number: Int,
    val titleRes: Int,
    val descRes: Int,
)

/** The four-step pipeline. Static content — no backend read, nothing to verify. */
fun howItWorksSteps(): List<HowStep> = listOf(
    HowStep(1, R.string.how_step1_title, R.string.how_step1_desc),
    HowStep(2, R.string.how_step2_title, R.string.how_step2_desc),
    HowStep(3, R.string.how_step3_title, R.string.how_step3_desc),
    HowStep(4, R.string.how_step4_title, R.string.how_step4_desc),
)

/** Layer icon, resolved at render time so the pure mapping never touches Compose. */
private fun ShieldLayerId.icon(): ImageVector = when (this) {
    ShieldLayerId.NETWORK -> Icons.Filled.Dns
    ShieldLayerId.LOCK -> Icons.Filled.Timer
    ShieldLayerId.TELEGRAM -> Icons.Filled.Chat
    ShieldLayerId.BYPASS -> Icons.Filled.Block
    ShieldLayerId.INSTALL -> Icons.Filled.Security
    ShieldLayerId.RECOVERY -> Icons.Filled.Sync
}

@Composable
private fun ShieldLayerStatus.dotColor() = when (this) {
    ShieldLayerStatus.ACTIVE -> SciFiColors.GoGreen
    ShieldLayerStatus.READY -> SciFiColors.SolarAmber
    ShieldLayerStatus.UNAVAILABLE -> colorScheme.error
}

@Composable
private fun ShieldLayerStatus.label(): String = when (this) {
    ShieldLayerStatus.ACTIVE -> stringResource(R.string.state_active)
    ShieldLayerStatus.READY -> stringResource(R.string.state_ready)
    ShieldLayerStatus.UNAVAILABLE -> stringResource(R.string.state_unavailable)
}

/**
 * The hero shield orb: a large glowing status icon that slowly breathes while
 * protection is genuinely running, and sits still otherwise. Animation only —
 * the icon, the glow colour and the pulsing itself are all pure functions of
 * already-mapped presentation facts, so the orb can never claim what the
 * backend has not verified.
 */
@Composable
fun ShieldOrb(
    icon: ImageVector,
    glow: Color,
    breathing: Boolean,
    modifier: Modifier = Modifier,
) {
    val transition = rememberInfiniteTransition(label = "orb")
    val pulse by transition.animateFloat(
        initialValue = 0.94f,
        targetValue = 1.05f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 1800, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "orbPulse",
    )
    val halo by transition.animateFloat(
        initialValue = 0.35f,
        targetValue = 0.75f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 1800, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "orbHalo",
    )
    val scale = if (breathing) pulse else 1f

    Box(
        modifier = modifier.size(128.dp),
        contentAlignment = Alignment.Center,
    ) {
        // Outer breathing halo.
        Box(
            modifier = Modifier
                .size(124.dp)
                .graphicsLayer {
                    scaleX = scale
                    scaleY = scale
                    alpha = if (breathing) halo else 0.25f
                }
                .background(glow.copy(alpha = 0.28f), CircleShape),
        )
        // Solid core with the status icon.
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
}

/**
 * The six guard layers with their live statuses. Each row expands on tap to a
 * second-depth plain-language line. Expansion is local UI state only — it
 * changes nothing about protection and reaches no backend.
 */
@Composable
fun ShieldLayersSection(state: ProtectionUiState) {
    val layers = shieldLayers(state)
    var expandedId by remember { mutableStateOf<ShieldLayerId?>(null) }

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = colorScheme.surface),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text(
                text = stringResource(R.string.features_section),
                style = typography.titleMedium,
                fontWeight = FontWeight.Bold,
            )
            Text(
                text = stringResource(R.string.features_section_detail),
                style = typography.bodySmall,
                color = colorScheme.onSurfaceVariant,
            )
            Text(
                text = stringResource(R.string.layer_tap_hint),
                style = typography.labelSmall,
                color = SciFiColors.NeonCyan.copy(alpha = 0.8f),
            )
            layers.forEach { layer ->
                val expanded = expandedId == layer.id
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable {
                            SciFiSound.blip()
                            expandedId = if (expanded) null else layer.id
                        }
                        .padding(vertical = 7.dp),
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        Box(
                            modifier = Modifier
                                .size(40.dp)
                                .background(colorScheme.primaryContainer, CircleShape),
                            contentAlignment = Alignment.Center,
                        ) {
                            Icon(
                                imageVector = layer.id.icon(),
                                contentDescription = null,
                                modifier = Modifier.size(22.dp),
                                tint = colorScheme.onPrimaryContainer,
                            )
                        }
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = stringResource(layer.titleRes),
                                style = typography.bodyMedium,
                                fontWeight = FontWeight.Medium,
                            )
                            Text(
                                text = stringResource(layer.descRes),
                                style = typography.bodySmall,
                                color = colorScheme.onSurfaceVariant,
                            )
                        }
                        Icon(
                            imageVector = Icons.Filled.ExpandMore,
                            contentDescription = null,
                            tint = colorScheme.onSurfaceVariant,
                            modifier = Modifier
                                .size(20.dp)
                                .graphicsLayer { rotationZ = if (expanded) 180f else 0f },
                        )
                        Column(horizontalAlignment = Alignment.End) {
                            Box(
                                modifier = Modifier
                                    .size(10.dp)
                                    .background(layer.status.dotColor(), CircleShape),
                            )
                            Text(
                                text = layer.status.label(),
                                style = typography.labelSmall,
                                color = colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                    AnimatedVisibility(
                        visible = expanded,
                        enter = fadeIn() + expandVertically(),
                        exit = fadeOut() + shrinkVertically(),
                    ) {
                        Text(
                            text = stringResource(layer.moreRes),
                            style = typography.bodySmall,
                            color = SciFiColors.NeonCyan.copy(alpha = 0.9f),
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(start = 52.dp, top = 6.dp, end = 4.dp),
                        )
                    }
                }
            }
        }
    }
}

/** The four-step pipeline. Static content, rendering only. */
@Composable
fun HowItWorksSection() {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = colorScheme.surface),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Text(
                text = stringResource(R.string.how_section),
                style = typography.titleMedium,
                fontWeight = FontWeight.Bold,
            )
            Text(
                text = stringResource(R.string.how_section_detail),
                style = typography.bodySmall,
                color = colorScheme.onSurfaceVariant,
            )
            howItWorksSteps().forEach { step ->
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Box(
                        modifier = Modifier
                            .size(32.dp)
                            .background(SciFiColors.NeonCyan.copy(alpha = 0.18f), CircleShape),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            text = step.number.toString(),
                            style = typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            color = SciFiColors.NeonCyan,
                        )
                    }
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = stringResource(step.titleRes),
                            style = typography.bodyMedium,
                            fontWeight = FontWeight.Medium,
                        )
                        Text(
                            text = stringResource(step.descRes),
                            style = typography.bodySmall,
                            color = colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
    }
}
