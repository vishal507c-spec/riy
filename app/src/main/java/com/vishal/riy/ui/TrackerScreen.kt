package com.vishal.riy.ui

import androidx.compose.foundation.Canvas
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme.colorScheme
import androidx.compose.material3.MaterialTheme.typography
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
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
 *
 * Cognitive-load rules for this screen: one clear primary action (today's
 * answer) at the top, numbers grouped in one tight grid, one trend picture
 * instead of walls of text, destructive actions behind a confirmation, and
 * settings parked at the bottom out of the way.
 */
@Composable
fun TrackerScreen(
    state: TrackerUiState,
    onBack: () -> Unit,
    onPreviousMonth: () -> Unit,
    onNextMonth: () -> Unit,
    onDayClick: (Long) -> Unit,
    onSetToday: (TrackerStatus?) -> Unit,
    onUndo: () -> Unit,
    onSetStreakFreeze: (Boolean) -> Unit,
    onBulkMark: (TrackerStatus) -> Unit,
    onClearMonth: () -> Unit,
    onExportCsv: () -> Unit,
    reminderEnabled: Boolean,
    onReminderEnabled: (Boolean) -> Unit,
    reminderHour: Int,
    onReminderHour: (Int) -> Unit,
    reminderMinute: Int,
    onReminderMinute: (Int) -> Unit,
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
                SavedBanner(onUndo = onUndo)
            }

            TodayCard(todayStatus = state.todayStatus, onSetToday = onSetToday)

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

            StreakCard(
                currentStreak = state.stats.currentStreak,
                longestStreak = state.stats.longestStreak,
                streakFrozen = state.stats.streakFrozen,
                frozenStreak = state.stats.frozenStreak,
                onSetFreeze = onSetStreakFreeze,
            )

            TrendAndInsights(state)

            CalendarSection(
                state = state,
                onPreviousMonth = onPreviousMonth,
                onNextMonth = onNextMonth,
                onDayClick = onDayClick,
                onBulkMark = onBulkMark,
                onClearMonth = onClearMonth,
            )

            ReminderCard(
                enabled = reminderEnabled,
                hour = reminderHour,
                minute = reminderMinute,
                onEnabled = onReminderEnabled,
                onHour = onReminderHour,
                onMinute = onReminderMinute,
            )

            OutlinedButton(
                onClick = onExportCsv,
                modifier = Modifier.fillMaxWidth().height(48.dp),
                shape = AppUI.CardRadius,
            ) {
                Text(
                    text = stringResource(R.string.tracker_export_csv),
                    style = typography.titleSmall,
                    color = colorScheme.onSurface,
                )
            }
            Text(
                text = stringResource(R.string.tracker_export_hint),
                style = typography.bodySmall,
                color = colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

@Composable
private fun SavedBanner(onUndo: () -> Unit) {
    SectionCard(container = SciFiColors.GoGreen.copy(alpha = 0.14f)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = stringResource(R.string.tracker_saved),
                style = typography.titleSmall,
                color = SciFiColors.GoGreen,
                fontWeight = FontWeight.SemiBold,
            )
            TextButton(onClick = onUndo) {
                Text(
                    text = stringResource(R.string.tracker_undo_last),
                    style = typography.titleSmall,
                    color = colorScheme.onSurface,
                )
            }
        }
    }
}

/**
 * The one primary action on this screen: answer today, right now, without
 * hunting through the calendar. Three explicit buttons — no guessing what a
 * tap cycle would do.
 */
@Composable
private fun TodayCard(todayStatus: TrackerStatus?, onSetToday: (TrackerStatus?) -> Unit) {
    SectionCard(container = colorScheme.surfaceVariant) {
        Text(
            text = stringResource(R.string.tracker_today_label),
            style = typography.labelLarge,
            color = colorScheme.onSurfaceVariant,
            fontWeight = FontWeight.SemiBold,
        )
        Spacer(Modifier.height(6.dp))
        Text(
            text = if (todayStatus == null) {
                stringResource(R.string.tracker_today_pending)
            } else {
                stringResource(
                    R.string.tracker_today_recorded,
                    when (todayStatus) {
                        TrackerStatus.NO -> stringResource(R.string.tracker_answer_no)
                        TrackerStatus.YES -> stringResource(R.string.tracker_answer_yes)
                        TrackerStatus.UNKNOWN -> stringResource(R.string.tracker_answer_unknown)
                    },
                )
            },
            style = typography.titleMedium,
            color = if (todayStatus == null) colorScheme.onSurfaceVariant else colorScheme.onSurface,
            fontWeight = FontWeight.Medium,
        )
        Spacer(Modifier.height(12.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TodayAnswerPill(
                label = stringResource(R.string.tracker_answer_no),
                accent = SciFiColors.GoGreen,
                selected = todayStatus == TrackerStatus.NO,
                onClick = { onSetToday(if (todayStatus == TrackerStatus.NO) null else TrackerStatus.NO) },
            )
            TodayAnswerPill(
                label = stringResource(R.string.tracker_answer_yes),
                accent = SciFiColors.SolarAmber,
                selected = todayStatus == TrackerStatus.YES,
                onClick = { onSetToday(if (todayStatus == TrackerStatus.YES) null else TrackerStatus.YES) },
            )
            TodayAnswerPill(
                label = stringResource(R.string.tracker_answer_unknown),
                accent = SciFiColors.Telemetry,
                selected = todayStatus == TrackerStatus.UNKNOWN,
                onClick = { onSetToday(if (todayStatus == TrackerStatus.UNKNOWN) null else TrackerStatus.UNKNOWN) },
            )
        }
    }
}

@Composable
private fun RowScope.TodayAnswerPill(
    label: String,
    accent: Color,
    selected: Boolean,
    onClick: () -> Unit,
) {
    Box(
        modifier = Modifier
            .weight(1f)
            .height(46.dp)
            .background(
                if (selected) accent.copy(alpha = 0.22f) else colorScheme.surface,
                AppUI.PillRadius,
            )
            .border(
                1.dp,
                if (selected) accent else colorScheme.onSurfaceVariant.copy(alpha = 0.25f),
                AppUI.PillRadius,
            )
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = label,
            style = typography.titleSmall,
            fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
            color = if (selected) accent else colorScheme.onSurface,
        )
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

/** Streak facts in one line, plus the streak-freeze pause toggle. */
@Composable
private fun StreakCard(
    currentStreak: Int,
    longestStreak: Int,
    streakFrozen: Boolean,
    frozenStreak: Int,
    onSetFreeze: (Boolean) -> Unit,
) {
    SectionCard {
        SectionLabel(stringResource(R.string.tracker_streak_breakdown_title))
        Spacer(Modifier.height(8.dp))
        Text(
            text = stringResource(
                R.string.tracker_streak_breakdown_body,
                currentStreak,
                longestStreak,
            ),
            style = typography.bodyMedium,
            color = colorScheme.onSurface,
        )
        if (streakFrozen && frozenStreak > 0) {
            Spacer(Modifier.height(6.dp))
            Text(
                text = stringResource(R.string.tracker_streak_frozen_note, frozenStreak),
                style = typography.bodySmall,
                color = SciFiColors.NeonCyan,
            )
        }
        Spacer(Modifier.height(10.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = stringResource(R.string.tracker_streak_freeze),
                    style = typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    text = stringResource(R.string.tracker_streak_freeze_desc),
                    style = typography.bodySmall,
                    color = colorScheme.onSurfaceVariant,
                )
            }
            Spacer(Modifier.width(12.dp))
            Switch(checked = streakFrozen, onCheckedChange = onSetFreeze)
        }
    }
}

@Composable
private fun CalendarSection(
    state: TrackerUiState,
    onPreviousMonth: () -> Unit,
    onNextMonth: () -> Unit,
    onDayClick: (Long) -> Unit,
    onBulkMark: (TrackerStatus) -> Unit,
    onClearMonth: () -> Unit,
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

        Spacer(Modifier.height(14.dp))
        BulkActions(onBulkMark = onBulkMark, onClearMonth = onClearMonth)
    }
}

/** Bulk actions for the displayed month, each behind a confirmation. */
@Composable
private fun BulkActions(onBulkMark: (TrackerStatus) -> Unit, onClearMonth: () -> Unit) {
    val pendingMark = remember { mutableStateOf<TrackerStatus?>(null) }
    val pendingClear = remember { mutableStateOf(false) }

    SectionLabel(stringResource(R.string.tracker_bulk_mark))
    Spacer(Modifier.height(8.dp))
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        BulkChip(label = stringResource(R.string.tracker_answer_no), accent = SciFiColors.GoGreen) {
            pendingMark.value = TrackerStatus.NO
        }
        BulkChip(label = stringResource(R.string.tracker_answer_yes), accent = SciFiColors.SolarAmber) {
            pendingMark.value = TrackerStatus.YES
        }
        BulkChip(label = stringResource(R.string.tracker_answer_unknown), accent = SciFiColors.Telemetry) {
            pendingMark.value = TrackerStatus.UNKNOWN
        }
    }
    Spacer(Modifier.height(10.dp))
    OutlinedButton(
        onClick = { pendingClear.value = true },
        modifier = Modifier.fillMaxWidth().height(44.dp),
        shape = AppUI.CardRadius,
    ) {
        Text(
            text = stringResource(R.string.tracker_bulk_clear),
            style = typography.titleSmall,
            color = colorScheme.error,
        )
    }

    pendingMark.value?.let { status ->
        val label = when (status) {
            TrackerStatus.NO -> stringResource(R.string.tracker_answer_no)
            TrackerStatus.YES -> stringResource(R.string.tracker_answer_yes)
            TrackerStatus.UNKNOWN -> stringResource(R.string.tracker_answer_unknown)
        }
        AlertDialog(
            onDismissRequest = { pendingMark.value = null },
            title = { Text(stringResource(R.string.tracker_bulk_mark)) },
            text = { Text(stringResource(R.string.tracker_bulk_mark_confirm, label)) },
            confirmButton = {
                TextButton(onClick = {
                    pendingMark.value = null
                    onBulkMark(status)
                }) { Text(stringResource(R.string.tracker_save_continue)) }
            },
            dismissButton = {
                TextButton(onClick = { pendingMark.value = null }) {
                    Text(stringResource(R.string.tracker_later))
                }
            },
        )
    }

    if (pendingClear.value) {
        AlertDialog(
            onDismissRequest = { pendingClear.value = false },
            title = { Text(stringResource(R.string.tracker_bulk_clear)) },
            text = { Text(stringResource(R.string.tracker_bulk_clear_confirm)) },
            confirmButton = {
                TextButton(onClick = {
                    pendingClear.value = false
                    onClearMonth()
                }) { Text(stringResource(R.string.tracker_save_continue)) }
            },
            dismissButton = {
                TextButton(onClick = { pendingClear.value = false }) {
                    Text(stringResource(R.string.tracker_later))
                }
            },
        )
    }
}

@Composable
private fun BulkChip(label: String, accent: Color, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .background(accent.copy(alpha = 0.18f), AppUI.PillRadius)
            .border(1.dp, accent.copy(alpha = 0.6f), AppUI.PillRadius)
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 10.dp),
    ) {
        Text(text = label, style = typography.titleSmall, fontWeight = FontWeight.SemiBold, color = accent)
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
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                text = dayOfMonthLabel(day.epochDay),
                style = typography.bodyMedium,
                color = if (day.status == null) colorScheme.onSurfaceVariant else colorScheme.onSurface,
                fontWeight = if (day.isToday) FontWeight.Bold else FontWeight.Normal,
            )
            if (day.hasNote) {
                // Small dot marks a day carrying a journal note.
                Box(
                    Modifier
                        .padding(top = 1.dp)
                        .size(4.dp)
                        .background(colorScheme.onSurface.copy(alpha = 0.7f), CircleShape),
                )
            }
        }
    }
}

private fun dayOfMonthLabel(epochDay: Long): String =
    TrackerDates.civilFromDays(epochDay).third.toString()

/**
 * One trend picture instead of two ribbons and a wall of numbers: the last
 * 15 days as bars, plus the same honest insight counts as before.
 */
@Composable
private fun TrendAndInsights(state: TrackerUiState) {
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
            Spacer(Modifier.height(12.dp))
            Text(
                text = stringResource(R.string.tracker_trend_title),
                style = typography.labelSmall,
                color = colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(6.dp))
            TrendChart(statuses = recent + state.todayStatus)
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

        if (state.stats.unrecordedDays > 0) {
            Spacer(Modifier.height(4.dp))
            Text(
                text = stringResource(R.string.tracker_unrecorded_note, state.stats.unrecordedDays),
                style = typography.bodySmall,
                color = colorScheme.onSurfaceVariant,
            )
        }

        Spacer(Modifier.height(6.dp))
        Text(
            text = stringResource(R.string.tracker_legend),
            style = typography.bodySmall,
            color = colorScheme.onSurfaceVariant,
        )
    }
}

/** A 15-day bar strip: one glance instead of reading two text ribbons. */
@Composable
private fun TrendChart(statuses: List<TrackerStatus?>) {
    Canvas(
        modifier = Modifier
            .fillMaxWidth()
            .height(52.dp),
    ) {
        val barWidth = size.width / statuses.size
        statuses.forEachIndexed { index, status ->
            val color = when (status) {
                TrackerStatus.NO -> SciFiColors.GoGreen
                TrackerStatus.YES -> SciFiColors.SolarAmber
                TrackerStatus.UNKNOWN -> SciFiColors.Telemetry
                null -> Color.White.copy(alpha = 0.12f)
            }
            drawRect(
                color = color,
                topLeft = Offset(index * barWidth, 0f),
                size = Size(barWidth * 0.72f, size.height),
            )
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

/** Reminder time stepper, parked at the bottom so it never interrupts logging. */
@Composable
private fun ReminderCard(
    enabled: Boolean,
    hour: Int,
    minute: Int,
    onEnabled: (Boolean) -> Unit,
    onHour: (Int) -> Unit,
    onMinute: (Int) -> Unit,
) {
    SectionCard {
        SectionLabel(stringResource(R.string.tracker_reminder_settings))
        Spacer(Modifier.height(8.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = stringResource(R.string.tracker_reminder_enabled),
                    style = typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    text = stringResource(R.string.tracker_reminder_time),
                    style = typography.bodySmall,
                    color = colorScheme.onSurfaceVariant,
                )
            }
            Spacer(Modifier.width(12.dp))
            Switch(checked = enabled, onCheckedChange = onEnabled)
        }
        if (enabled) {
            Spacer(Modifier.height(10.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                TimeStepper(
                    label = "HH",
                    value = hour,
                    onDecrease = { onHour((hour + 23) % 24) },
                    onIncrease = { onHour((hour + 1) % 24) },
                )
                TimeStepper(
                    label = "MM",
                    value = minute,
                    onDecrease = { onMinute((minute + 59) % 60) },
                    onIncrease = { onMinute((minute + 1) % 60) },
                )
            }
        }
    }
}

@Composable
private fun TimeStepper(label: String, value: Int, onDecrease: () -> Unit, onIncrease: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            text = label,
            style = typography.labelSmall,
            color = colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(end = 6.dp),
        )
        TextButton(onClick = onDecrease) {
            Text(text = "−", style = typography.titleMedium, color = colorScheme.primary)
        }
        Text(
            text = String.format(Locale.US, "%02d", value),
            style = typography.titleMedium,
            fontWeight = FontWeight.Bold,
            color = colorScheme.onSurface,
        )
        TextButton(onClick = onIncrease) {
            Text(text = "+", style = typography.titleMedium, color = colorScheme.primary)
        }
    }
}

private fun monthLabel(epochDay: Long): String {
    val (year, month, _) = TrackerDates.civilFromDays(epochDay)
    val name = java.text.DateFormatSymbols(Locale.US).getShortMonths().getOrNull(month - 1).orEmpty()
    return "$name $year".uppercase(Locale.US)
}
