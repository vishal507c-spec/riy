package com.vishal.riy.ui

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme.colorScheme
import androidx.compose.material3.MaterialTheme.typography
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp

/**
 * Purely decorative sci-fi cockpit chrome: targeting-corner brackets around
 * the screen, a "R.I.Y. // PLANETARY SHIELD" header strip and a slow radar
 * sweep line. Renders nothing a test could mistake for protection state —
 * all status text still comes from the mapped strings.
 */
@Composable
fun SciFiFrame(
    header: String,
    headerColor: Color,
    modifier: Modifier = Modifier,
    content: @Composable BoxScope.() -> Unit,
) {
    Box(modifier = modifier.fillMaxSize()) {
        CornerBrackets(color = headerColor, modifier = Modifier.fillMaxSize())
        Text(
            text = header,
            style = typography.labelLarge,
            color = headerColor.copy(alpha = 0.75f),
            textAlign = TextAlign.Center,
            modifier = Modifier
                .fillMaxWidth()
                .align(Alignment.TopCenter)
                .padding(top = 10.dp),
        )
        content()
    }
}

@Composable
private fun CornerBrackets(color: Color, modifier: Modifier = Modifier) {
    Canvas(modifier = modifier.padding(8.dp)) {
        val len = 42f
        val stroke = 5f
        val w = size.width
        val h = size.height
        val pts = listOf(
            // top-left
            listOf(Offset(0f, len), Offset(0f, 0f), Offset(len, 0f)),
            // top-right
            listOf(Offset(w - len, 0f), Offset(w, 0f), Offset(w, len)),
            // bottom-left
            listOf(Offset(0f, h - len), Offset(0f, h), Offset(len, h)),
            // bottom-right
            listOf(Offset(w - len, h), Offset(w, h), Offset(w, h - len)),
        )
        pts.forEach { poly ->
            drawLine(color.copy(alpha = 0.85f), poly[0], poly[1], stroke)
            drawLine(color.copy(alpha = 0.85f), poly[1], poly[2], stroke)
        }
    }
}

/**
 * A slow horizontal holo sweep that drifts down the given area and loops —
 * the "scanner" feel. Height is fixed by the caller.
 */
@Composable
fun ScanSweep(modifier: Modifier = Modifier, color: Color = SciFiColors.NeonCyan) {
    val transition = rememberInfiniteTransition(label = "scan")
    val progress by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 2800, easing = LinearEasing),
            repeatMode = RepeatMode.Restart,
        ),
        label = "scanProgress",
    )
    Canvas(
        modifier = modifier
            .fillMaxWidth()
            .height(120.dp)
            .padding(horizontal = 4.dp),
    ) {
        val y = (progress * size.height).coerceIn(2f, size.height - 2f)
        drawRect(
            brush = Brush.horizontalGradient(
                colors = listOf(
                    Color.Transparent,
                    color.copy(alpha = 0.9f),
                    Color.Transparent,
                ),
            ),
            topLeft = Offset(0f, y - 2f),
            size = androidx.compose.ui.geometry.Size(size.width, 4f),
        )
    }
}
