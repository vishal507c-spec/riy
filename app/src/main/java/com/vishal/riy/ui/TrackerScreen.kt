package com.vishal.riy.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme.colorScheme
import androidx.compose.material3.MaterialTheme.typography
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.vishal.riy.R
import com.vishal.riy.tracker.TrackerCalendarDay
import com.vishal.riy.tracker.TrackerStatus
import com.vishal.riy.tracker.TrackerUiState
import java.util.Locale
import java.util.TimeZone

/**
 * The Daily Tracker screen.
 *
 * Purely presentational: every number comes from [TrackerUiState], which is
 * derived from what is actually stored. There are no sample values, no
 * placeholders dressed as data, and no level/XP/progress-bar decoration — the
 * brief explicitly rules those out, and invented encouragement would be
 * misinformation about the user's own history.
 *
 * Colour meaning, kept consistent everywhere in this feature:
 *   green = NO (a confirmed "no" day), amber = YES, grey = not recorded/unknown.
 */
@Composable
fun TrackerScreen(
    state: TrackerUiState,
    onBack: () -> Unit,
    onPreviousMonth: () -> Unit,
    onNextMonth: () -> Unit,
onDayClick: (Long) -> Unit,
    onConfirmationShown: () -> Unit = {},
    modifier: Modifier = Modifier,
) {
    SciFiFrame(
        header = "R.I.Y. // DAILY TRACKER",
        headerColor = SciFiColors.GoGreen,
        modifier = modifier,
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp, vertical = 40.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Text(
                text = stringResource(R.string.tracker_title),
                style = typography.headlineMedium,
                color = colorScheme.onBackground,
            )
            Text(
                text = stringResource(R.string.tracker_subtitle),
                style = typography.bodyMedium,
                color = colorScheme.onSurfaceVariant,
            )

            // Small, non-blocking save confirmation.
            state.savedConfirmation?.let {
                LaunchedEffect(it) {
                    kotlinx.coroutines.delay(2_500)
                    onConfirmationShown()
                }
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(14.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = SciFiColors.GoGreen.copy(alpha = 0.15f),
                    ),
                ) {
                    Text(
                        text = stringResource(R.string.tracker_saved),
                        style = typography.titleMedium,
                        color = SciFiColors.GoGreen,
                        modifier = Modifier.padding(horizontal = 20.dp, vertical = 14.dp),
                    )
                }
            }

            StatCard(
                label = stringResource(R.string.tracker_stat_yes_days),
                value = state.stats.yesDays,
                accent = SciFiColors.SolarAmber,
            )
            StatCard(
                label = stringResource(R.string.tracker_stat_no_days),
                value = state.stats.noDays,
                accent = SciFiColors.GoGreen,
            )
            StatCard(
                label = stringResource(R.string.tracker_stat_current_streak),
                value = state.stats.currentStreak,
                accent = SciFiColors.NeonCyan,
            )
            StatCard(
                label = stringResource(R.string.tracker_stat_longest_streak),
                value = state.stats.longestStreak,
                accent = SciFiColors.NeonCyan,
            )

            if (state.stats.unrecordedDays > 0) {
                Text(
                    text = stringResource(
                        R.string.tracker_unrecorded_note,
                        state.stats.unrecordedDays,
                    ),
                    style = typography.bodySmall,
                    color = colorScheme.onSurfaceVariant,
                )
            }

            CalendarSection(
                state = state,
                onPreviousMonth = onPreviousMonth,
                onNextMonth = onNextMonth,
                onDayClick = onDayClick,
            )

            Text(
                text = stringResource(R.string.tracker_legend),
                style = typography.bodySmall,
                color = colorScheme.onSurfaceVariant,
            )

            Text(
                text = stringResource(R.string.tracker_back),
                style = typography.labelLarge,
                color = colorScheme.primary,
                modifier = Modifier
                    .padding(top = 8.dp)
                    .clickable(onClick = onBack),
            )
        }
    }
}

@Composable
private fun StatCard(label: String, value: Int, accent: Color) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = colorScheme.surface),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(
                text = label,
                style = typography.labelLarge,
                color = colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f),
            )
            Text(
                // A real number, or an honest zero. Never a fabricated sample.
                text = value.toString(),
                style = typography.headlineMedium,
                fontWeight = FontWeight.Bold,
                color = accent,
            )
        }
    }
}

@Composable
private fun CalendarSection(
    state: TrackerUiState,
    onPreviousMonth: () -> Unit,
    onNextMonth: () -> Unit,
    onDayClick: (Long) -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = colorScheme.surface),
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                text = stringResource(R.string.tracker_calendar_header),
                style = typography.labelLarge,
                color = colorScheme.onSurfaceVariant,
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = monthLabel(state.monthAnchor),
                    style = typography.titleLarge,
                    color = colorScheme.onSurface,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(18.dp)) {
                    Text(
                        text = "<",
                        style = typography.titleLarge,
                        color = colorScheme.primary,
                        modifier = Modifier
                            .clickable(onClick = onPreviousMonth)
                            .padding(horizontal = 4.dp),
                    )
                    Text(
                        text = ">",
                        style = typography.titleLarge,
                        color = colorScheme.primary,
                        modifier = Modifier
                            .clickable(onClick = onNextMonth)
                            .padding(horizontal = 4.dp),
                    )
                }
            }

            Row(Modifier.fillMaxWidth()) {
                listOf("S", "M", "T", "W", "T", "F", "S").forEach { label ->
                    Text(
                        text = label,
                        style = typography.bodySmall,
                        color = colorScheme.onSurfaceVariant,
                        modifier = Modifier.weight(1f),
                    )
                }
            }

            // Compose lays a Row's children out side by side, so the month is
            // emitted as explicit week rows. Each cell takes an equal weight,
            // which is what keeps 1..31 aligned under the weekday headers.
            val cells: List<TrackerCalendarDay?> =
                List<TrackerCalendarDay?>(state.leadingBlanks) { null } + state.days
            val padded = cells + List((WEEK_SIZE - cells.size % WEEK_SIZE) % WEEK_SIZE) { null }
            padded.chunked(WEEK_SIZE).forEach { week ->
                Row(Modifier.fillMaxWidth()) {
                    week.forEach { day ->
                        if (day == null) {
                            Box(
                                Modifier
                                    .weight(1f)
                                    .aspectRatio(1f),
                            )
                        } else {
                            DayCell(day = day, onClick = { onDayClick(day.epochDay) })
                        }
                    }
                }
            }
        }
    }
}

private const val WEEK_SIZE = 7

/** A RowScope extension so the cell can take an equal share of the week row. */
@Composable
private fun RowScope.DayCell(day: TrackerCalendarDay, onClick: () -> Unit) {
    val (tint, border) = when (day.status) {
        TrackerStatus.NO -> SciFiColors.GoGreen to SciFiColors.GoGreen
        TrackerStatus.YES -> SciFiColors.SolarAmber to SciFiColors.SolarAmber
        TrackerStatus.UNKNOWN -> Color(0xFF5A6B7A) to Color(0xFF5A6B7A)
        null -> Color(0xFF16232F) to Color(0xFF1E3242)
    }
    Box(
        modifier = Modifier
            .weight(1f)
            .aspectRatio(1f)
            .padding(2.dp)
            .background(tint.copy(alpha = if (day.status == null) 0.55f else 0.20f), CircleShape)
            .border(1.dp, border.copy(alpha = 0.8f), CircleShape)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = dayOfMonthLabel(day.epochDay),
            style = typography.bodyMedium,
            color = if (day.status == null) colorScheme.onSurfaceVariant else colorScheme.onSurface,
            fontWeight = if (day.isToday) FontWeight.Bold else FontWeight.Normal,
        )
    }
}

private fun dayOfMonthLabel(epochDay: Long): String =
    com.vishal.riy.tracker.TrackerDates.civilFromDays(epochDay).third.toString()

private fun monthLabel(epochDay: Long): String {
    val (year, month, _) = com.vishal.riy.tracker.TrackerDates.civilFromDays(epochDay)
    val name = java.text.DateFormatSymbols(Locale.US)
        .getShortMonths()
        .getOrNull(month - 1)
        .orEmpty()
        .uppercase(Locale.US)
    return "$name $year"
}