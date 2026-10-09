package com.vishal.riy.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
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
import androidx.compose.ui.unit.dp
import com.vishal.riy.R
import com.vishal.riy.tracker.TrackerDates
import com.vishal.riy.tracker.TrackerStatus

/**
 * The check-in sheet: one question, one explicit date, three answers.
 *
 * THE most important detail on this screen is that the date is rendered
 * prominently and is passed through to the save call unchanged. The failure mode
 * being designed against is answering "yesterday" and having it filed under
 * today; that can only happen if the target date is ever re-derived from the
 * clock at save time, so it is threaded explicitly instead.
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
        header = "R.I.Y. // CHECK-IN",
        headerColor = SciFiColors.GoGreen,
        modifier = modifier,
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 24.dp, vertical = 48.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Text(
                text = stringResource(R.string.tracker_greeting),
                style = typography.headlineMedium,
                color = colorScheme.onBackground,
            )

            Text(
                text = stringResource(R.string.tracker_question),
                style = typography.titleLarge,
                color = colorScheme.onSurface,
            )

            // The date under discussion, stated explicitly and unambiguously.
            Text(
                text = TrackerDates.formatDate(epochDay, java.util.TimeZone.getDefault()),
                style = typography.displayLarge,
                color = SciFiColors.NeonCyan,
            )
            Text(
                text = stringResource(R.string.tracker_date_hint),
                style = typography.bodySmall,
                color = colorScheme.onSurfaceVariant,
            )

            AnswerButton(
                label = stringResource(R.string.tracker_answer_yes),
                accent = SciFiColors.SolarAmber,
                selected = selected == TrackerStatus.YES,
                onClick = { selected = TrackerStatus.YES },
            )
            AnswerButton(
                label = stringResource(R.string.tracker_answer_no),
                accent = SciFiColors.GoGreen,
                selected = selected == TrackerStatus.NO,
                onClick = { selected = TrackerStatus.NO },
            )
            AnswerButton(
                label = stringResource(R.string.tracker_answer_unknown),
                accent = Color(0xFF5A6B7A),
                selected = selected == TrackerStatus.UNKNOWN,
                onClick = { selected = TrackerStatus.UNKNOWN },
            )

            Button(
                onClick = { selected?.let { onSave(epochDay, it) } },
                enabled = selected != null,
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(18.dp),
                colors = ButtonDefaults.buttonColors(containerColor = colorScheme.primary),
            ) {
                Text(
                    text = stringResource(R.string.tracker_save_continue),
                    style = typography.titleMedium,
                )
            }

            OutlinedButton(
                onClick = onDismiss,
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(18.dp),
            ) {
                Text(
                    text = stringResource(R.string.tracker_later),
                    style = typography.bodyLarge,
                    color = colorScheme.onSurfaceVariant,
                )
            }

            if (onDelete != null) {
                Text(
                    text = stringResource(R.string.tracker_delete_record),
                    style = typography.bodyMedium,
                    color = colorScheme.error,
                    modifier = Modifier
                        .padding(top = 4.dp)
                        .clickable(onClick = onDelete),
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
    Card(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (selected) accent.copy(alpha = 0.22f) else colorScheme.surface,
        ),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = 18.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            androidx.compose.foundation.layout.Box(
                Modifier
                    .size(14.dp)
                    .background(if (selected) accent else Color(0xFF1E3242), CircleShape),
            )
            Text(
                text = label,
                style = typography.titleMedium,
                color = if (selected) accent else colorScheme.onSurface,
            )
        }
    }
}