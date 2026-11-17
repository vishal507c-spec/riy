package com.vishal.riy.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily

/**
 * RIY shield-console theme: calm deep-navy surfaces, soft cyan primary, mint
 * for verified states and amber for anything not yet proven. Always dark.
 *
 * Only colours and typography live here. Every protection string, icon and
 * state mapping stays in the authoritative presentation code.
 */
object SciFiColors {
    val Void = Color(0xFF070B14)
    val Hull = Color(0xFF0D1524)
    val Console = Color(0xFF121E31)
    val NeonCyan = Color(0xFF4DD8E6)
    val CyanDepth = Color(0xFF10333E)
    val Plasma = Color(0xFFB169F5)
    val SolarAmber = Color(0xFFFFC14D)
    val AlertRed = Color(0xFFFF6B5E)
    val GoGreen = Color(0xFF4ADE9B)
    val Telemetry = Color(0xFF93A7BD)
}

private val ShieldScheme = darkColorScheme(
    primary = SciFiColors.NeonCyan,
    onPrimary = SciFiColors.Void,
    primaryContainer = SciFiColors.CyanDepth,
    onPrimaryContainer = SciFiColors.NeonCyan,
    secondary = SciFiColors.Plasma,
    onSecondary = SciFiColors.Void,
    secondaryContainer = Color(0xFF221630),
    onSecondaryContainer = SciFiColors.Plasma,
    tertiary = SciFiColors.SolarAmber,
    onTertiary = SciFiColors.Void,
    tertiaryContainer = Color(0xFF2E2210),
    onTertiaryContainer = SciFiColors.SolarAmber,
    background = SciFiColors.Void,
    onBackground = Color(0xFFEAF2FA),
    surface = SciFiColors.Hull,
    onSurface = Color(0xFFEAF2FA),
    surfaceVariant = SciFiColors.Console,
    onSurfaceVariant = SciFiColors.Telemetry,
    error = SciFiColors.AlertRed,
    onError = SciFiColors.Void,
    errorContainer = Color(0xFF351514),
    onErrorContainer = Color(0xFFFFB4AB),
)

private val ShieldTypography = Typography().let { base ->
    base.copy(
        labelLarge = base.labelLarge.copy(fontFamily = FontFamily.Monospace),
        labelSmall = base.labelSmall.copy(fontFamily = FontFamily.Monospace),
    )
}

/** App theme: always the dark shield console. */
@Composable
fun RiyTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = ShieldScheme, typography = ShieldTypography, content = content)
}
