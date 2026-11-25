package com.vishal.riy.ui

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.vishal.riy.R
import com.vishal.riy.tracker.FlameSegment
import com.vishal.riy.tracker.InsightTone
import com.vishal.riy.tracker.TrackerCalendarDay
import com.vishal.riy.tracker.TrackerDates
import com.vishal.riy.tracker.TrackerInsight
import com.vishal.riy.tracker.TrackerInsights
import com.vishal.riy.tracker.TrackerStats
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
        header = "RIY // INNER FLAME",
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

            state.savedConfirmation?.let {
                LaunchedEffect(it) {
                    kotlinx.coroutines.delay(2_500)
                    onConfirmationShown()
                }
                SavedBanner(onUndo = onUndo)
            }

            TodayCard(todayStatus = state.todayStatus, onSetToday = onSetToday)

            FlameCard(
                stats = state.stats,
                onSetFreeze = onSetStreakFreeze,
            )

            // Four real numbers as ONE calm surface — one card, not four.
            SectionCard {
                SectionLabel(stringResource(R.string.tracker_numbers_title))
                Spacer(Modifier.height(12.dp))
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                        MetricCell(
                            label = stringResource(R.string.tracker_stat_no_days),
                            value = state.stats.noDays,
                            caption = stringResource(R.string.tracker_hint_no),
                            accent = SciFiColors.GoGreen,
                        )
                        MetricCell(
                            label = stringResource(R.string.tracker_stat_current_streak),
                            value = state.stats.currentStreak,
                            caption = stringResource(R.string.tracker_hint_current_streak),
                            accent = SciFiColors.NeonCyan,
                        )
                    }
                    Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                        MetricCell(
                            label = stringResource(R.string.tracker_stat_yes_days),
                            value = state.stats.yesDays,
                            caption = stringResource(R.string.tracker_hint_yes),
                            accent = SciFiColors.SolarAmber,
                        )
                        MetricCell(
                            label = stringResource(R.string.tracker_stat_longest_streak),
                            value = state.stats.longestStreak,
                            caption = stringResource(R.string.tracker_hint_longest_streak),
                            accent = SciFiColors.NeonCyan,
                        )
                    }
                }
            }

            TrackerStatusLine(state)

            FlameHistoryCard(segments = state.stats.flameSegments)

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

/**
 * The INNER FLAME hero: when the flame is burning (kept since), when it last
 * broke, and the freeze pause. The dates are real record dates — the user
 * always sees exactly when the flame was kept and when it went out.
 */
@Composable
private fun FlameCard(
    stats: TrackerStats,
    onSetFreeze: (Boolean) -> Unit,
) {
    val tz = TimeZone.getDefault()
    val alive = stats.currentStreak > 0
    // Smoothly animate the flame's mood so a break or a repair feels like a
    // state change, not a jump cut.
    val accent by animateColorAsState(
        targetValue = if (alive) SciFiColors.GoGreen else SciFiColors.SolarAmber,
        animationSpec = tween(450),
        label = "flameAccent",
    )
    val container by animateColorAsState(
        targetValue = if (alive) SciFiColors.GoGreen.copy(alpha = 0.12f)
        else SciFiColors.SolarAmber.copy(alpha = 0.12f),
        animationSpec = tween(450),
        label = "flameContainer",
    )
    // A living flame breathes; a broken one still.
    val infinite = rememberInfiniteTransition(label = "flamePulse")
    val pulse by infinite.animateFloat(
        initialValue = 0.94f,
        targetValue = if (alive) 1.10f else 0.94f,
        animationSpec = infiniteRepeatable(tween(900), RepeatMode.Reverse),
        label = "flameScale",
    )
    SectionCard(container = container) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = if (alive) "🔥" else "💔",
                style = typography.headlineMedium,
                modifier = Modifier.graphicsLayer {
                    scaleX = pulse
                    scaleY = pulse
                },
            )
            Spacer(Modifier.width(12.dp))
            Column {
                Text(
                    text = stringResource(R.string.tracker_flame_title),
                    style = typography.labelLarge,
                    color = colorScheme.onSurfaceVariant,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    text = if (alive) {
                        stringResource(
                            R.string.tracker_flame_burning,
                            stats.currentStreak,
                            TrackerDates.formatDate(stats.flameStartDay!!, tz),
                        )
                    } else {
                        stats.lastBreakDay?.let {
                            stringResource(R.string.tracker_flame_broken, TrackerDates.formatDate(it, tz))
                        } ?: stringResource(R.string.tracker_flame_never_kept)
                    },
                    style = typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = accent,
                )
            }
        }

        Spacer(Modifier.height(8.dp))
        Text(
            text = stats.lastBreakDay?.let {
                stringResource(R.string.tracker_flame_last_break, TrackerDates.formatDate(it, tz))
            } ?: stringResource(R.string.tracker_flame_never_broke),
            style = typography.bodySmall,
            color = colorScheme.onSurfaceVariant,
        )
        if (stats.streakFrozen && stats.frozenStreak > 0) {
            Spacer(Modifier.height(4.dp))
            Text(
                text = stringResource(R.string.tracker_streak_frozen_note, stats.frozenStreak),
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
            Switch(checked = stats.streakFrozen, onCheckedChange = onSetFreeze)
        }
    }
}

/** Recent flame runs, so "when did I keep it" is visible at a glance. */
@Composable
private fun FlameHistoryCard(segments: List<FlameSegment>) {
    if (segments.isEmpty()) return
    val tz = TimeZone.getDefault()
    SectionCard {
        SectionLabel(stringResource(R.string.tracker_flame_history))
        Spacer(Modifier.height(8.dp))
        segments.forEach { segment ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = "🔥",
                    style = typography.bodyMedium,
                )
                Spacer(Modifier.width(10.dp))
                Text(
                    text = stringResource(
                        R.string.tracker_flame_segment,
                        TrackerDates.formatDate(segment.startDay, tz),
                        TrackerDates.formatDate(segment.endDay, tz),
                        segment.length,
                    ),
                    style = typography.bodyMedium,
                    color = colorScheme.onSurface,
                    modifier = Modifier.weight(1f),
                )
            }
            Spacer(Modifier.height(6.dp))
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

        Spacer(Modifier.height(12.dp))
        Text(
            text = stringResource(R.string.tracker_legend),
            style = typography.bodySmall,
            color = colorScheme.onSurfaceVariant,
        )
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
    // Today gets a bright ring so "where am I in the month" is instant.
    val ring = if (day.isToday) SciFiColors.NeonCyan else border
    Box(
        modifier = Modifier
            .weight(1f)
            .aspectRatio(1f)
            .padding(3.dp)
            .background(tint.copy(alpha = if (day.status == null) 0.55f else 0.20f), CircleShape)
            .border(
                if (day.isToday) 2.dp else 1.dp,
                ring.copy(alpha = if (day.isToday) 1f else 0.8f),
                CircleShape,
            )
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
            Spacer(Modifier.height(4.dp))
            TrendChartLabels()
            Spacer(Modifier.height(12.dp))
        }

        val insights = TrackerInsights.all(state.stats, recent, state.todayStatus)
        Spacer(Modifier.height(4.dp))
        // One swipeable row instead of five stacked rows: the same honest
        // numbers, far less vertical noise.
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            insights.forEach { insight ->
                InsightChip(insight = insight)
            }
        }

        if (state.stats.unrecordedDays > 0) {
            Spacer(Modifier.height(12.dp))
            Text(
                text = stringResource(R.string.tracker_unrecorded_note, state.stats.unrecordedDays),
                style = typography.bodySmall,
                color = colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun InsightChip(insight: TrackerInsight) {
    val valueColor = when (insight.tone) {
        InsightTone.GOOD -> SciFiColors.GoGreen
        InsightTone.NEUTRAL -> SciFiColors.SolarAmber
        InsightTone.QUIET -> colorScheme.onSurface
    }
    Column(
        modifier = Modifier
            .background(colorScheme.surface, AppUI.PillRadius)
            .border(1.dp, colorScheme.onSurfaceVariant.copy(alpha = 0.25f), AppUI.PillRadius)
            .padding(horizontal = 14.dp, vertical = 10.dp),
    ) {
        Text(
            text = stringResource(insight.labelRes),
            style = typography.labelSmall,
            color = colorScheme.onSurfaceVariant,
        )
        Text(
            text = insight.value,
            style = typography.titleMedium,
            fontWeight = FontWeight.Bold,
            color = valueColor,
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
        // Faint baseline so unrecorded days read as "empty", not as bars.
        drawRect(
            color = Color.White.copy(alpha = 0.10f),
            topLeft = Offset(0f, size.height - 2f),
            size = Size(size.width, 2f),
        )
    }
}

/** Where the strip starts and ends — removes all guesswork from the chart. */
@Composable
private fun TrendChartLabels() {
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(
            text = stringResource(R.string.tracker_trend_start),
            style = typography.labelSmall,
            color = colorScheme.onSurfaceVariant,
        )
        Text(
            text = stringResource(R.string.tracker_trend_end),
            style = typography.labelSmall,
            color = colorScheme.onSurfaceVariant,
        )
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
