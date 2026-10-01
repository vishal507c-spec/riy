package com.vishal.riy.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily

/**
 * RIY sci-fi cockpit theme: deep-space hull, neon-cyan holograms, magenta
 * accents, amber warnings. Always dark — a starship bridge has no light mode.
 *
 * Only colors/typography live here; every protection string, icon and state
 * mapping is untouched (see the zero-confusion contract).
 */
object SciFiColors {
    /** Hull black. */
    val Void = Color(0xFF04070D)

    /** Panel surface. */
    val Hull = Color(0xFF0A1420)

    /** Raised console surface. */
    val Console = Color(0xFF0E1C2E)

    /** Hologram cyan (primary actions, active states). */
    val NeonCyan = Color(0xFF00E5FF)

    /** Deep cyan for filled containers. */
    val CyanDepth = Color(0xFF062B36)

    /** Plasma magenta (escalated states, secondary accents). */
    val Plasma = Color(0xFFFF3DF0)

    /** Warning amber (transitional states). */
    val SolarAmber = Color(0xFFFFB300)

    /** Alert red-orange (failures, mismatches). */
    val AlertRed = Color(0xFFFF4D3D)

    /** Go green (verified states). */
    val GoGreen = Color(0xFF3DFF88)

    /** Dim telemetry text. */
    val Telemetry = Color(0xFF7D93A8)
}

private val SciFiScheme = darkColorScheme(
    primary = SciFiColors.NeonCyan,
    onPrimary = Color(0xFF001318),
    primaryContainer = SciFiColors.CyanDepth,
    onPrimaryContainer = SciFiColors.NeonCyan,
    secondary = SciFiColors.Plasma,
    onSecondary = Color(0xFF1B0019),
    secondaryContainer = Color(0xFF2E0A2C),
    onSecondaryContainer = SciFiColors.Plasma,
    tertiary = SciFiColors.SolarAmber,
    onTertiary = Color(0xFF1B1000),
    tertiaryContainer = Color(0xFF2E1F04),
    onTertiaryContainer = SciFiColors.SolarAmber,
    background = SciFiColors.Void,
    onBackground = Color(0xFFE8F4FF),
    surface = SciFiColors.Hull,
    onSurface = Color(0xFFE8F4FF),
    surfaceVariant = SciFiColors.Console,
    onSurfaceVariant = SciFiColors.Telemetry,
    error = SciFiColors.AlertRed,
    onError = Color(0xFF1B0500),
    errorContainer = Color(0xFF330D08),
    onErrorContainer = Color(0xFFFF8A7A),
)

private val SciFiTypography = Typography().let { base ->
    val mono = FontFamily.Monospace
    base.copy(
        displayLarge = base.displayLarge.copy(fontFamily = mono),
        headlineMedium = base.headlineMedium.copy(fontFamily = mono),
        titleLarge = base.titleLarge.copy(fontFamily = mono),
        labelLarge = base.labelLarge.copy(fontFamily = mono, letterSpacing = base.labelLarge.letterSpacing),
    )
}

/**
 * App theme: always the sci-fi cockpit (dark). Reuses the existing Material3
 * DayNight XML theme on the Activity window.
 */
@Composable
fun RiyTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = SciFiScheme, typography = SciFiTypography, content = content)
}
