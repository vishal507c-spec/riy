package com.vishal.riy.ui

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext

/**
 * App theme: Material You dynamic colors on Android 12+, standard light/dark
 * otherwise. Reuses the existing Material3 DayNight XML theme on the Activity.
 */
@Composable
fun RiyTheme(content: @Composable () -> Unit) {
    val dark = isSystemInDarkTheme()
    val context = LocalContext.current
    val scheme = when {
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.S ->
            if (dark) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        dark -> darkColorScheme()
        else -> lightColorScheme()
    }
    MaterialTheme(colorScheme = scheme, content = content)
}

/**
 * Root composable: main protection screen <-> in-app protection test screen.
 */
@Composable
fun RiyApp() {
    RiyTheme {
        var showTest by rememberSaveable { mutableStateOf(false) }
        if (showTest) {
            ProtectionTestScreen(onBack = { showTest = false })
        } else {
            ProtectionScreen(onOpenProtectionTest = { showTest = true })
        }
    }
}
