package com.vishal.riy.ui

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme.colorScheme
import androidx.compose.material3.MaterialTheme.typography
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp

/**
 * Quiet app frame: a thin accent rule, a small header strip and correct
 * content colour. Purely decorative — all status text still comes from the
 * authoritative presentation mapping.
 */
@Composable
fun SciFiFrame(
    header: String,
    headerColor: Color,
    modifier: Modifier = Modifier,
    content: @Composable BoxScope.() -> Unit,
) {
    CompositionLocalProvider(LocalContentColor provides colorScheme.onBackground) {
        Box(modifier = modifier.fillMaxSize().background(colorScheme.background)) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(2.dp)
                    .background(
                        Brush.horizontalGradient(
                            listOf(Color.Transparent, headerColor, Color.Transparent),
                        ),
                    ),
            )
            Text(
                text = header,
                style = typography.labelSmall,
                color = headerColor.copy(alpha = 0.85f),
                textAlign = TextAlign.Center,
                modifier = Modifier
                    .fillMaxWidth()
                    .align(Alignment.TopCenter)
                    .padding(top = 10.dp),
            )
            content()
        }
    }
}

/** A subtle looping scan line — visual dressing only. */
@Composable
fun ScanSweep(modifier: Modifier = Modifier, color: Color = SciFiColors.NeonCyan) {
    val transition = rememberInfiniteTransition(label = "scan")
    val progress by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 3000, easing = LinearEasing),
            repeatMode = RepeatMode.Restart,
        ),
        label = "scanProgress",
    )
    Canvas(
        modifier = modifier
            .fillMaxWidth()
            .height(40.dp)
            .padding(horizontal = 8.dp),
    ) {
        val y = (progress * size.height).coerceIn(1f, size.height - 1f)
        drawLine(
            brush = Brush.horizontalGradient(
                listOf(Color.Transparent, color.copy(alpha = 0.5f), Color.Transparent),
            ),
            start = Offset(0f, y),
            end = Offset(size.width, y),
            strokeWidth = 2f,
        )
    }
}
