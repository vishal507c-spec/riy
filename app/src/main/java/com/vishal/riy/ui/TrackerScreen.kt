package com.vishal.riy.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
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
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.vishal.riy.R
import com.vishal.riy.tracker.InsightTone
import com.vishal.riy.tracker.TrackerCalendarDay
import com.vishal.riy.tracker.TrackerDates
import com.vishal.riy.tracker.TrackerInsights
import com.vishal.riy.tracker.TrackerStatus
import com.vishal.riy.tracker.TrackerUiState
import java.util.Locale
import java.util.TimeZone

/**
 * Daily Tracker — a calm, data-honest screen. Every number comes from
 * [TrackerUiState]; nothing is sampled or invented. Green = NO, amber = YES,
 * grey = not recorded.
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
        header = "RIY // DAILY TRACKER",
        headerColor = SciFiColors.GoGreen,
        modifier = modifier,
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = AppUI.ScreenPadding, vertical = 40.dp),
            verticalArrangement = Arrangement.spacedBy(AppUI.SectionGap),
        ) {
            AppHeader(
                title = stringResource(R.string.tracker_title),
                subtitle = stringResource(R.string.tracker_entry_subtitle),
                trailing = {
                    Text(
                        text = "‹",
                        style = typography.headlineMedium,
                        color = colorScheme.primary,
                        modifier = Modifier
                            .clickable(onClick = onBack)
                            .padding(horizontal = 12.dp, vertical = 4.dp),
                    )
                },
            )

            Text(
                text = stringResource(R.string.tracker_subtitle),
                style = typography.bodyMedium,
                color = colorScheme.onSurfaceVariant,
            )

            state.savedConfirmation?.let {
                LaunchedEffect(it) {
                    kotlinx.coroutines.delay(2_500)
                    onConfirmationShown()
                }
                SectionCard(container = SciFiColors.GoGreen.copy(alpha = 0.14f)) {
                    Text(
                        text = stringResource(R.string.tracker_saved),
                        style = typography.titleSmall,
                        color = SciFiColors.GoGreen,
                        fontWeight = FontWeight.SemiBold,
                    )
                }
            }

            // Four real numbers in a balanced 2x2 grid.
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    MetricTile(
                        label = stringResource(R.string.tracker_stat_no_days),
                        value = state.stats.noDays,
                        caption = stringResource(R.string.tracker_hint_no),
                        accent = SciFiColors.GoGreen,
                    )
                    MetricTile(
                        label = stringResource(R.string.tracker_stat_current_streak),
                        value = state.stats.currentStreak,
                        caption = stringResource(R.string.tracker_hint_current_streak),
                        accent = SciFiColors.NeonCyan,
                    )
                }
                Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    MetricTile(
                        label = stringResource(R.string.tracker_stat_yes_days),
                        value = state.stats.yesDays,
                        caption = stringResource(R.string.tracker_hint_yes),
                        accent = SciFiColors.SolarAmber,
                    )
                    MetricTile(
                        label = stringResource(R.string.tracker_stat_longest_streak),
                        value = state.stats.longestStreak,
                        caption = stringResource(R.string.tracker_hint_longest_streak),
                        accent = SciFiColors.NeonCyan,
                    )
                }
            }

            TrackerStatusLine(state)

            WeekAndInsights(state)

            if (state.stats.unrecordedDays > 0) {
                Text(
                    text = stringResource(R.string.tracker_unrecorded_note, state.stats.unrecordedDays),
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
        }
    }
}

@Composable
private fun TrackerStatusLine(state: TrackerUiState) {
    val stats = state.stats
    val text = when {
        stats.recordedDays == 0 -> stringResource(R.string.tracker_status_empty)
        stats.latestRecordedDay != null -> stringResource(
            R.string.tracker_status_summary,
            stats.recordedDays,
            TrackerDates.formatDate(stats.latestRecordedDay, TimeZone.getDefault()),
            TrackerDates.formatDate(stats.trackingStartDay!!, TimeZone.getDefault()),
        )
        else -> stringResource(R.string.tracker_status_empty)
    }
    SectionCard(container = if (stats.recordedDays == 0) colorScheme.surfaceVariant
    else SciFiColors.GoGreen.copy(alpha = 0.12f)) {
        Text(text = text, style = typography.bodyMedium, color = colorScheme.onSurface)
    }
}

@Composable
private fun CalendarSection(
    state: TrackerUiState,
    onPreviousMonth: () -> Unit,
    onNextMonth: () -> Unit,
    onDayClick: (Long) -> Unit,
) {
    SectionCard {
        SectionLabel(stringResource(R.string.tracker_calendar_header))
        Spacer(Modifier.height(10.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(text = monthLabel(state.monthAnchor), style = typography.titleLarge, fontWeight = FontWeight.Bold)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                NavArrow("<", onPreviousMonth)
                NavArrow(">", onNextMonth)
            }
        }
        Spacer(Modifier.height(12.dp))
        Row(Modifier.fillMaxWidth()) {
            listOf("S", "M", "T", "W", "T", "F", "S").forEach { label ->
                Text(
                    text = label,
                    style = typography.bodySmall,
                    color = colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.weight(1f),
                )
            }
        }
        Spacer(Modifier.height(6.dp))
        val cells: List<TrackerCalendarDay?> =
            List<TrackerCalendarDay?>(state.leadingBlanks) { null } + state.days
        val padded = cells + List((WEEK_SIZE - cells.size % WEEK_SIZE) % WEEK_SIZE) { null }
        padded.chunked(WEEK_SIZE).forEach { week ->
            Row(Modifier.fillMaxWidth()) {
                week.forEach { day ->
                    if (day == null) {
                        Box(Modifier.weight(1f).aspectRatio(1f))
                    } else {
                        DayCell(day = day, onClick = { onDayClick(day.epochDay) })
                    }
                }
            }
        }
    }
}

@Composable
private fun NavArrow(symbol: String, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .size(34.dp)
            .clickable(onClick = onClick)
            .background(colorScheme.primaryContainer, CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        Text(text = symbol, style = typography.titleMedium, color = colorScheme.primary)
    }
}

private const val WEEK_SIZE = 7

@Composable
private fun RowScope.DayCell(day: TrackerCalendarDay, onClick: () -> Unit) {
    val (tint, border) = when (day.status) {
        TrackerStatus.NO -> SciFiColors.GoGreen to SciFiColors.GoGreen
        TrackerStatus.YES -> SciFiColors.SolarAmber to SciFiColors.SolarAmber
        TrackerStatus.UNKNOWN -> SciFiColors.Telemetry to SciFiColors.Telemetry
        null -> Color(0xFF16232F) to Color(0xFF1E3242)
    }
    Box(
        modifier = Modifier
            .weight(1f)
            .aspectRatio(1f)
            .padding(3.dp)
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
    TrackerDates.civilFromDays(epochDay).third.toString()

@Composable
private fun WeekAndInsights(state: TrackerUiState) {
    val recent = state.recentStatuses
    SectionCard {
        SectionLabel(stringResource(R.string.tracker_insights_title))
        Spacer(Modifier.height(12.dp))

        if (recent.isNotEmpty()) {
            Text(
                text = stringResource(R.string.tracker_week_title),
                style = typography.labelSmall,
                color = colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(6.dp))
            StatusRibbon(recent.takeLast(7))
            Spacer(Modifier.height(10.dp))
            Text(
                text = stringResource(R.string.tracker_pattern_title),
                style = typography.labelSmall,
                color = colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(6.dp))
            StatusRibbon(recent)
            Spacer(Modifier.height(14.dp))
        }

        val insights = TrackerInsights.all(state.stats, recent, state.todayStatus)
        insights.chunked(2).forEach { pair ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                pair.forEach { insight ->
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = stringResource(insight.labelRes),
                            style = typography.labelSmall,
                            color = colorScheme.onSurfaceVariant,
                        )
                        Text(
                            text = insight.value,
                            style = typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            color = when (insight.tone) {
                                InsightTone.GOOD -> SciFiColors.GoGreen
                                InsightTone.NEUTRAL -> SciFiColors.SolarAmber
                                InsightTone.QUIET -> colorScheme.onSurface
                            },
                        )
                        Spacer(Modifier.height(8.dp))
                    }
                }
            }
        }
    }
}

@Composable
private fun StatusRibbon(statuses: List<TrackerStatus?>) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        statuses.forEach { status ->
            val color = when (status) {
                TrackerStatus.NO -> SciFiColors.GoGreen
                TrackerStatus.YES -> SciFiColors.SolarAmber
                TrackerStatus.UNKNOWN -> SciFiColors.Telemetry
                null -> colorScheme.surface
            }
            Box(
                modifier = Modifier
                    .weight(1f)
                    .background(color.copy(alpha = if (status == null) 1f else 0.55f), CircleShape)
                    .height(10.dp),
            )
        }
    }
}

private fun monthLabel(epochDay: Long): String {
    val (year, month, _) = TrackerDates.civilFromDays(epochDay)
    val name = java.text.DateFormatSymbols(Locale.US).getShortMonths().getOrNull(month - 1).orEmpty()
    return "$name $year".uppercase(Locale.US)
}
