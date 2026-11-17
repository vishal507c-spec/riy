package com.vishal.riy.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme.colorScheme
import androidx.compose.material3.MaterialTheme.typography
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp

/** Shared visual tokens — one source of truth for spacing and radii. */
object AppUI {
    val ScreenPadding = 20.dp
    val SectionGap = 14.dp
    val CardRadius = RoundedCornerShape(18.dp)
    val PillRadius = RoundedCornerShape(999.dp)
}

/** Consistent screen header: brand mark, title, optional trailing slot. */
@Composable
fun AppHeader(
    title: String,
    subtitle: String? = null,
    trailing: @Composable (() -> Unit)? = null,
) {
    Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Box(
            modifier = Modifier
                .size(36.dp)
                .background(SciFiColors.CyanDepth, CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            Text("R", color = SciFiColors.NeonCyan, fontWeight = FontWeight.Bold)
        }
        Spacer(Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(text = title, style = typography.titleMedium, fontWeight = FontWeight.Bold)
            if (subtitle != null) {
                Text(text = subtitle, style = typography.labelSmall, color = colorScheme.onSurfaceVariant)
            }
        }
        trailing?.invoke()
    }
}

/** A standard elevated surface used by every section. */
@Composable
fun SectionCard(
    modifier: Modifier = Modifier,
    container: Color = colorScheme.surfaceVariant,
    content: @Composable ColumnScope.() -> Unit,
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        shape = AppUI.CardRadius,
        colors = CardDefaults.cardColors(containerColor = container),
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(18.dp),
            content = content,
        )
    }
}

@Composable
fun SectionLabel(text: String) {
    Text(
        text = text,
        style = typography.labelLarge,
        color = colorScheme.onSurfaceVariant,
        fontWeight = FontWeight.SemiBold,
    )
}

/** A single honest status line: dot + label + supporting text. */
@Composable
fun StatusLine(label: String, detail: String, color: Color) {
    Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(9.dp).background(color, CircleShape))
        Spacer(Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(text = label, style = typography.titleMedium, fontWeight = FontWeight.Bold)
            Text(text = detail, style = typography.bodySmall, color = colorScheme.onSurfaceVariant)
        }
    }
}

/** A metric tile for the tracker's four real numbers. */
@Composable
fun MetricTile(label: String, value: Int, caption: String, accent: Color) {
    SectionCard {
        Text(text = label, style = typography.labelMedium, color = colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(8.dp))
        Row(verticalAlignment = Alignment.Bottom) {
            Text(text = value.toString(), style = typography.headlineMedium, fontWeight = FontWeight.Bold, color = accent)
            Spacer(Modifier.width(6.dp))
            Text(text = caption, style = typography.bodySmall, color = colorScheme.onSurfaceVariant)
        }
        Spacer(Modifier.height(4.dp))
        Text(text = caption, style = typography.bodySmall, color = colorScheme.onSurfaceVariant)
    }
}
