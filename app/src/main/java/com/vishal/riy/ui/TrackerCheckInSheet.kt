package com.vishal.riy.ui

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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme.colorScheme
import androidx.compose.material3.MaterialTheme.typography
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import java.util.TimeZone
import com.vishal.riy.R
import com.vishal.riy.tracker.TrackerDates
import com.vishal.riy.tracker.TrackerStatus

/**
 * Check-in: one question, one explicit date, three answers. The date is passed
 * through unchanged to save — it is never re-derived from the clock.
 */
@Composable
fun TrackerCheckInSheet(
    epochDay: Long,
    existingStatus: TrackerStatus?,
    onSave: (Long, TrackerStatus) -> Unit,
    onDismiss: () -> Unit,
    onDelete: (() -> Unit)?,
    modifier: Modifier = Modifier,
) {
    var selected by remember(epochDay) { mutableStateOf(existingStatus) }

    SciFiFrame(
        header = "RIY // DAILY CHECK-IN",
        headerColor = SciFiColors.GoGreen,
        modifier = modifier,
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = AppUI.ScreenPadding, vertical = 48.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            AppHeader(
                title = stringResource(R.string.tracker_greeting),
                subtitle = stringResource(R.string.tracker_date_hint),
                trailing = {
                    Text(
                        text = "‹",
                        style = typography.headlineMedium,
                        color = colorScheme.primary,
                        modifier = Modifier.clickable(onClick = onDismiss).padding(horizontal = 12.dp),
                    )
                },
            )

            SectionCard {
                Text(
                    text = stringResource(R.string.tracker_question),
                    style = typography.titleMedium,
                    color = colorScheme.onSurface,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(10.dp))
                Text(
                    text = TrackerDates.formatDate(epochDay, TimeZone.getDefault()),
                    style = typography.headlineSmall,
                    color = SciFiColors.NeonCyan,
                    fontWeight = FontWeight.Bold,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth(),
                )
            }

            AnswerButton(
                label = stringResource(R.string.tracker_answer_no),
                accent = SciFiColors.GoGreen,
                selected = selected == TrackerStatus.NO,
            ) { selected = TrackerStatus.NO }

            AnswerButton(
                label = stringResource(R.string.tracker_answer_yes),
                accent = SciFiColors.SolarAmber,
                selected = selected == TrackerStatus.YES,
            ) { selected = TrackerStatus.YES }

            AnswerButton(
                label = stringResource(R.string.tracker_answer_unknown),
                accent = SciFiColors.Telemetry,
                selected = selected == TrackerStatus.UNKNOWN,
            ) { selected = TrackerStatus.UNKNOWN }

            Spacer(Modifier.height(6.dp))

            Button(
                onClick = { selected?.let { onSave(epochDay, it) } },
                enabled = selected != null,
                modifier = Modifier.fillMaxWidth().height(52.dp),
                shape = AppUI.CardRadius,
                colors = ButtonDefaults.buttonColors(
                    containerColor = colorScheme.primary,
                    contentColor = colorScheme.onPrimary,
                ),
            ) {
                Text(text = stringResource(R.string.tracker_save_continue), style = typography.titleSmall)
            }

            OutlinedButton(
                onClick = onDismiss,
                modifier = Modifier.fillMaxWidth().height(48.dp),
                shape = AppUI.CardRadius,
            ) {
                Text(text = stringResource(R.string.tracker_later), color = colorScheme.onSurfaceVariant)
            }

            if (onDelete != null) {
                Text(
                    text = stringResource(R.string.tracker_delete_record),
                    style = typography.bodyMedium,
                    color = colorScheme.error,
                    modifier = Modifier.clickable(onClick = onDelete).padding(8.dp),
                )
            }
        }
    }
}

@Composable
private fun AnswerButton(
    label: String,
    accent: Color,
    selected: Boolean,
    onClick: () -> Unit,
) {
    SectionCard(
        container = if (selected) accent.copy(alpha = 0.18f) else colorScheme.surface,
        modifier = Modifier.clickable(onClick = onClick),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier
                    .size(14.dp)
                    .background(if (selected) accent else colorScheme.onSurfaceVariant.copy(alpha = 0.4f), CircleShape),
            )
            Spacer(Modifier.size(14.dp))
            Text(
                text = label,
                style = typography.titleMedium,
                fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
                color = if (selected) accent else colorScheme.onSurface,
            )
        }
    }
}
