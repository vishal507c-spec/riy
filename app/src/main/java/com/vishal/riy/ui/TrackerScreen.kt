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
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
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
import com.vishal.riy.tracker.TrackerCalendarDay
import com.vishal.riy.tracker.TrackerDates
import com.vishal.riy.tracker.TrackerStats
import com.vishal.riy.tracker.TrackerStatus
import com.vishal.riy.tracker.TrackerUiState
import java.text.DateFormatSymbols
import java.util.Locale
import java.util.TimeZone

/**
 * Inner Flame — built around one question and three honest answers.
 *
 * Reading order matches how a person actually thinks:
 *   1. "How did today go?"  → answer right here
 *   2. "Is my flame burning?" → when it was kept and when it broke
 *   3. "How am I doing overall?" → three plain numbers and one picture
 *   4. "What happened in this month?" → calendar
 *   5. Settings (reminder, pause, sharing) stay closed at the bottom.
 *
 * Nothing here is a score, a level or a health claim. Every number is a count
 * of real saved dates.
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
        header = "RIY",
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

            FlameCard(stats = state.stats)

            JourneyCard(stats = state.stats)

            TrendCard(state)

            FlameHistoryCard(segments = state.stats.flameSegments)

            CalendarSection(
                state = state,
                onPreviousMonth = onPreviousMonth,
                onNextMonth = onNextMonth,
                onDayClick = onDayClick,
                onBulkMark = onBulkMark,
                onClearMonth = onClearMonth,
            )

            MoreCard(
                reminderEnabled = reminderEnabled,
                reminderHour = reminderHour,
                reminderMinute = reminderMinute,
                onReminderEnabled = onReminderEnabled,
                onReminderHour = onReminderHour,
                onReminderMinute = onReminderMinute,
                streakFrozen = state.stats.streakFrozen,
                frozenStreak = state.stats.frozenStreak,
                onSetFreeze = onSetStreakFreeze,
                onExportCsv = onExportCsv,
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
 * One question, three full-width answers. The answer is saved immediately, so
 * there is no hidden save step and no tap-cycling to guess about.
 */
@Composable
private fun TodayCard(todayStatus: TrackerStatus?, onSetToday: (TrackerStatus?) -> Unit) {
    SectionCard(container = colorScheme.surfaceVariant) {
        SectionLabel(stringResource(R.string.tracker_today_label))
        Spacer(Modifier.height(6.dp))
        Text(
            text = stringResource(R.string.tracker_today_question),
            style = typography.headlineSmall,
            fontWeight = FontWeight.Bold,
            color = colorScheme.onSurface,
        )
        Spacer(Modifier.height(4.dp))
        Text(
            text = if (todayStatus == null) {
                stringResource(R.string.tracker_today_pending)
            } else {
                stringResource(R.string.tracker_today_recorded, statusWord(todayStatus))
            },
            style = typography.bodyMedium,
            color = colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(12.dp))

        TodayAnswerRow(
            label = stringResource(R.string.tracker_answer_no),
            accent = SciFiColors.GoGreen,
            selected = todayStatus == TrackerStatus.NO,
            onClick = { onSetToday(if (todayStatus == TrackerStatus.NO) null else TrackerStatus.NO) },
        )
        Spacer(Modifier.height(8.dp))
        TodayAnswerRow(
            label = stringResource(R.string.tracker_answer_yes),
            accent = SciFiColors.SolarAmber,
            selected = todayStatus == TrackerStatus.YES,
            onClick = { onSetToday(if (todayStatus == TrackerStatus.YES) null else TrackerStatus.YES) },
        )
        Spacer(Modifier.height(8.dp))
        TodayAnswerRow(
            label = stringResource(R.string.tracker_answer_unknown),
            accent = SciFiColors.Telemetry,
            selected = todayStatus == TrackerStatus.UNKNOWN,
            onClick = { onSetToday(if (todayStatus == TrackerStatus.UNKNOWN) null else TrackerStatus.UNKNOWN) },
        )

        Spacer(Modifier.height(10.dp))
        Text(
            text = stringResource(R.string.tracker_today_hint),
            style = typography.bodySmall,
            color = colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun TodayAnswerRow(
    label: String,
    accent: Color,
    selected: Boolean,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(48.dp)
            .background(
                if (selected) accent.copy(alpha = 0.20f) else colorScheme.surface,
                AppUI.CardRadius,
            )
            .border(
                1.dp,
                if (selected) accent else colorScheme.onSurfaceVariant.copy(alpha = 0.25f),
                AppUI.CardRadius,
            )
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier
                .size(12.dp)
                .background(if (selected) accent else colorScheme.onSurfaceVariant.copy(alpha = 0.35f), CircleShape),
        )
        Spacer(Modifier.width(12.dp))
        Text(
            text = label,
            style = typography.titleSmall,
            fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
            color = if (selected) accent else colorScheme.onSurface,
            modifier = Modifier.weight(1f),
        )
        if (selected) {
            Text(text = "✓", style = typography.titleMedium, color = accent)
        }
    }
}

/**
 * The flame status: when it was kept, when it last broke, and the optional
 * pause. Every date is a real record date. A frozen run is shown as paused —
 * never as still burning — so the display can never lie.
 */
@Composable
private fun FlameCard(stats: TrackerStats) {
    val frozen = stats.streakFrozen && stats.frozenStreak > 0
    val alive = !frozen && stats.currentStreak > 0
    val hasRecords = stats.recordedDays > 0
    val accent by animateColorAsState(
        targetValue = when {
            frozen -> SciFiColors.NeonCyan
            alive -> SciFiColors.GoGreen
            else -> SciFiColors.SolarAmber
        },
        animationSpec = tween(450),
        label = "flameAccent",
    )
    val container by animateColorAsState(
        targetValue = when {
            frozen -> SciFiColors.NeonCyan.copy(alpha = 0.12f)
            alive -> SciFiColors.GoGreen.copy(alpha = 0.12f)
            hasRecords -> SciFiColors.SolarAmber.copy(alpha = 0.12f)
            else -> colorScheme.surfaceVariant
        },
        animationSpec = tween(450),
        label = "flameContainer",
    )
    // A living flame breathes; a paused or broken one stays still.
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
                text = when {
                    frozen -> "⏸️"
                    alive -> "🔥"
                    hasRecords -> "💔"
                    else -> "🕯️"
                },
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
                    text = when {
                        frozen -> stringResource(R.string.tracker_flame_frozen, stats.frozenStreak)
                        alive -> stringResource(R.string.tracker_flame_burning, stats.currentStreak)
                        hasRecords && stats.lastBreakDay != null -> stringResource(R.string.tracker_flame_broken)
                        else -> stringResource(R.string.tracker_flame_waiting)
                    },
                    style = typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = accent,
                )
            }
        }

        Spacer(Modifier.height(8.dp))
        Text(
            text = when {
                frozen -> stringResource(R.string.tracker_flame_frozen_hint)
                alive -> stringResource(
                    R.string.tracker_flame_since,
                    friendlyDate(stats.flameStartDay!!),
                )
                hasRecords && stats.lastBreakDay != null -> stringResource(
                    R.string.tracker_flame_broken_on,
                    friendlyDate(stats.lastBreakDay),
                )
                hasRecords -> stringResource(R.string.tracker_flame_saved_today)
                else -> stringResource(R.string.tracker_flame_waiting_hint)
            },
            style = typography.bodyMedium,
            color = colorScheme.onSurfaceVariant,
        )

        when {
            alive -> {
                Spacer(Modifier.height(4.dp))
                Text(
                    text = stats.lastBreakDay?.let {
                        stringResource(R.string.tracker_flame_last_break, friendlyDate(it))
                    } ?: stringResource(R.string.tracker_flame_never_broke),
                    style = typography.bodySmall,
                    color = colorScheme.onSurfaceVariant,
                )
            }
            hasRecords && !frozen -> {
                Spacer(Modifier.height(4.dp))
                Text(
                    text = stringResource(R.string.tracker_flame_relight),
                    style = typography.bodySmall,
                    color = accent,
                )
            }
        }
    }
}

/** Three real numbers, no duplicates of the flame card above. */
@Composable
private fun JourneyCard(stats: TrackerStats) {
    SectionCard {
        SectionLabel(stringResource(R.string.tracker_numbers_title))
        Spacer(Modifier.height(12.dp))
        Row(modifier = Modifier.fillMaxWidth()) {
            JourneyStat(
                value = stats.noDays,
                label = stringResource(R.string.tracker_stat_no_days),
                accent = SciFiColors.GoGreen,
            )
            JourneyStat(
                value = stats.yesDays,
                label = stringResource(R.string.tracker_stat_yes_days),
                accent = SciFiColors.SolarAmber,
            )
            JourneyStat(
                value = stats.longestStreak,
                label = stringResource(R.string.tracker_stat_longest_streak),
                accent = SciFiColors.NeonCyan,
            )
        }
        Spacer(Modifier.height(12.dp))
        Text(
            text = stringResource(R.string.tracker_journey_recorded, stats.recordedDays),
            style = typography.bodySmall,
            color = colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(2.dp))
        Text(
            text = stringResource(R.string.tracker_journey_best_hint),
            style = typography.bodySmall,
            color = colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun RowScope.JourneyStat(value: Int, label: String, accent: Color) {
    Column(
        modifier = Modifier.weight(1f),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = value.toString(),
            style = typography.headlineMedium,
            fontWeight = FontWeight.Bold,
            color = accent,
        )
        Spacer(Modifier.height(2.dp))
        Text(
            text = label,
            style = typography.labelSmall,
            color = colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
    }
}

/** One picture and one colour key — no duplicate ribbon and no chip wall. */
@Composable
private fun TrendCard(state: TrackerUiState) {
    SectionCard {
        SectionLabel(stringResource(R.string.tracker_trend_title))
        Spacer(Modifier.height(12.dp))
        if (state.recentStatuses.isEmpty()) {
            Text(
                text = stringResource(R.string.tracker_flame_never_kept),
                style = typography.bodySmall,
                color = colorScheme.onSurfaceVariant,
            )
        } else {
            TrendChart(statuses = state.recentStatuses + state.todayStatus)
            Spacer(Modifier.height(6.dp))
            TrendChartLabels()
            Spacer(Modifier.height(10.dp))
            StatusLegend()
        }
    }
}

/** The colour key, in words anybody can read. */
@Composable
private fun StatusLegend() {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            LegendItem(SciFiColors.GoGreen, stringResource(R.string.tracker_legend_clean))
            LegendItem(SciFiColors.SolarAmber, stringResource(R.string.tracker_legend_slipped))
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            LegendItem(SciFiColors.Telemetry, stringResource(R.string.tracker_legend_not_sure))
            LegendItem(
                colorScheme.onSurfaceVariant.copy(alpha = 0.55f),
                stringResource(R.string.tracker_legend_no_answer),
            )
        }
    }
}

@Composable
private fun LegendItem(color: Color, label: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(8.dp).background(color, CircleShape))
        Spacer(Modifier.width(6.dp))
        Text(
            text = label,
            style = typography.labelSmall,
            color = colorScheme.onSurfaceVariant,
        )
    }
}

/** A 15-day strip: filled bar = answered, small tick = no answer yet. */
@Composable
private fun TrendChart(statuses: List<TrackerStatus?>) {
    Canvas(
        modifier = Modifier
            .fillMaxWidth()
            .height(56.dp),
    ) {
        if (statuses.isEmpty()) return@Canvas
        val slot = size.width / statuses.size
        val barWidth = slot * 0.64f
        statuses.forEachIndexed { index, status ->
            val left = index * slot + (slot - barWidth) / 2f
            if (status == null) {
                drawRoundRect(
                    color = Color.White.copy(alpha = 0.14f),
                    topLeft = Offset(left, size.height - 5f),
                    size = Size(barWidth, 5f),
                    cornerRadius = CornerRadius(2f, 2f),
                )
            } else {
                val color = when (status) {
                    TrackerStatus.NO -> SciFiColors.GoGreen
                    TrackerStatus.YES -> SciFiColors.SolarAmber
                    TrackerStatus.UNKNOWN -> SciFiColors.Telemetry
                }
                drawRoundRect(
                    color = color,
                    topLeft = Offset(left, 0f),
                    size = Size(barWidth, size.height),
                    cornerRadius = CornerRadius(3f, 3f),
                )
            }
        }
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

/** "When did I keep it" — real kept runs, newest first. */
@Composable
private fun FlameHistoryCard(segments: List<FlameSegment>) {
    if (segments.isEmpty()) return
    SectionCard {
        SectionLabel(stringResource(R.string.tracker_flame_history))
        Spacer(Modifier.height(10.dp))
        segments.forEachIndexed { index, segment ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(Modifier.size(8.dp).background(SciFiColors.GoGreen, CircleShape))
                Spacer(Modifier.width(10.dp))
                Text(
                    text = stringResource(
                        R.string.tracker_flame_segment,
                        friendlyDate(segment.startDay),
                        friendlyDate(segment.endDay),
                    ),
                    style = typography.bodyMedium,
                    color = colorScheme.onSurface,
                    modifier = Modifier.weight(1f),
                )
                Text(
                    text = stringResource(R.string.tracker_flame_days, segment.length),
                    style = typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                    color = SciFiColors.GoGreen,
                )
            }
            if (index != segments.lastIndex) Spacer(Modifier.height(8.dp))
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
    var showTools by remember { mutableStateOf(false) }
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

        Spacer(Modifier.height(12.dp))
        TextButton(onClick = { showTools = !showTools }) {
            Text(
                text = stringResource(
                    if (showTools) R.string.tracker_month_tools_hide else R.string.tracker_month_tools,
                ),
                style = typography.titleSmall,
                color = colorScheme.primary,
            )
        }
        if (showTools) {
            Spacer(Modifier.height(6.dp))
            BulkActions(onBulkMark = onBulkMark, onClearMonth = onClearMonth)
        }

        Spacer(Modifier.height(8.dp))
        Text(
            text = stringResource(R.string.tracker_calendar_hint),
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

    Text(
        text = stringResource(R.string.tracker_bulk_mark),
        style = typography.titleSmall,
        fontWeight = FontWeight.SemiBold,
    )
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
        AlertDialog(
            onDismissRequest = { pendingMark.value = null },
            title = { Text(stringResource(R.string.tracker_bulk_mark)) },
            text = {
                Text(
                    stringResource(
                        R.string.tracker_bulk_mark_confirm,
                        statusWord(status),
                    ),
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    pendingMark.value = null
                    onBulkMark(status)
                }) { Text(stringResource(R.string.tracker_confirm)) }
            },
            dismissButton = {
                TextButton(onClick = { pendingMark.value = null }) {
                    Text(stringResource(R.string.tracker_cancel))
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
                }) { Text(stringResource(R.string.tracker_confirm)) }
            },
            dismissButton = {
                TextButton(onClick = { pendingClear.value = false }) {
                    Text(stringResource(R.string.tracker_cancel))
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
            .padding(horizontal = 12.dp, vertical = 10.dp),
    ) {
        Text(
            text = label,
            style = typography.bodyMedium,
            fontWeight = FontWeight.SemiBold,
            color = accent,
        )
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

/** Reminder, streak pause and sharing — closed by default, out of the way. */
@Composable
private fun MoreCard(
    reminderEnabled: Boolean,
    reminderHour: Int,
    reminderMinute: Int,
    onReminderEnabled: (Boolean) -> Unit,
    onReminderHour: (Int) -> Unit,
    onReminderMinute: (Int) -> Unit,
    streakFrozen: Boolean,
    frozenStreak: Int,
    onSetFreeze: (Boolean) -> Unit,
    onExportCsv: () -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    SectionCard {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { expanded = !expanded },
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                SectionLabel(stringResource(R.string.tracker_more_title))
                Spacer(Modifier.height(2.dp))
                Text(
                    text = if (reminderEnabled) {
                        stringResource(
                            R.string.tracker_reminder_on_summary,
                            formatTime(reminderHour, reminderMinute),
                        )
                    } else {
                        stringResource(R.string.tracker_reminder_off_summary)
                    },
                    style = typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    text = stringResource(R.string.tracker_more_subtitle),
                    style = typography.bodySmall,
                    color = colorScheme.onSurfaceVariant,
                )
            }
            Text(
                text = if (expanded) "−" else "+",
                style = typography.headlineSmall,
                color = SciFiColors.NeonCyan,
            )
        }

        if (expanded) {
            Spacer(Modifier.height(16.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = stringResource(R.string.tracker_reminder_settings),
                        style = typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Text(
                        text = stringResource(R.string.tracker_reminder_help),
                        style = typography.bodySmall,
                        color = colorScheme.onSurfaceVariant,
                    )
                }
                Spacer(Modifier.width(12.dp))
                Switch(checked = reminderEnabled, onCheckedChange = onReminderEnabled)
            }
            if (reminderEnabled) {
                Spacer(Modifier.height(10.dp))
                Text(
                    text = stringResource(R.string.tracker_reminder_time),
                    style = typography.bodySmall,
                    color = colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(4.dp))
                Row(
                    horizontalArrangement = Arrangement.spacedBy(16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    TimeStepper(
                        label = stringResource(R.string.tracker_hour),
                        value = reminderHour,
                        onDecrease = { onReminderHour((reminderHour + 23) % 24) },
                        onIncrease = { onReminderHour((reminderHour + 1) % 24) },
                    )
                    TimeStepper(
                        label = stringResource(R.string.tracker_minute),
                        value = reminderMinute,
                        onDecrease = { onReminderMinute((reminderMinute + 59) % 60) },
                        onIncrease = { onReminderMinute((reminderMinute + 1) % 60) },
                    )
                }
            }

            Spacer(Modifier.height(16.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
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
            if (streakFrozen) {
                Spacer(Modifier.height(4.dp))
                Text(
                    text = stringResource(R.string.tracker_streak_frozen_note, frozenStreak),
                    style = typography.bodySmall,
                    color = SciFiColors.NeonCyan,
                )
            }

            Spacer(Modifier.height(16.dp))
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
            Spacer(Modifier.height(6.dp))
            Text(
                text = stringResource(R.string.tracker_export_hint),
                style = typography.bodySmall,
                color = colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun TimeStepper(label: String, value: Int, onDecrease: () -> Unit, onIncrease: () -> Unit) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            text = label,
            style = typography.labelSmall,
            color = colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(4.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            StepButton("−", onDecrease)
            Text(
                text = String.format(Locale.US, "%02d", value),
                style = typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = colorScheme.onSurface,
                textAlign = TextAlign.Center,
                modifier = Modifier.width(34.dp),
            )
            StepButton("+", onIncrease)
        }
    }
}

@Composable
private fun StepButton(symbol: String, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .size(32.dp)
            .background(colorScheme.primaryContainer, CircleShape)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(text = symbol, style = typography.titleMedium, color = colorScheme.primary)
    }
}

@Composable
private fun statusWord(status: TrackerStatus): String = when (status) {
    TrackerStatus.NO -> stringResource(R.string.tracker_answer_no)
    TrackerStatus.YES -> stringResource(R.string.tracker_answer_yes)
    TrackerStatus.UNKNOWN -> stringResource(R.string.tracker_answer_unknown)
}

private fun monthLabel(epochDay: Long): String {
    val (year, month, _) = TrackerDates.civilFromDays(epochDay)
    val name = DateFormatSymbols(Locale.US).getShortMonths().getOrNull(month - 1).orEmpty()
    return "$name $year".uppercase(Locale.US)
}

/** Friendly date: "24 Nov 2026" instead of a bare number string. */
private fun friendlyDate(epochDay: Long): String {
    val (year, month, day) = TrackerDates.civilFromDays(epochDay)
    val name = DateFormatSymbols(Locale.US).getShortMonths().getOrNull(month - 1).orEmpty()
    return "$day $name $year"
}

/** "9:00 AM" — how a person says a reminder time. */
private fun formatTime(hour: Int, minute: Int): String {
    val displayHour = when {
        hour == 0 -> 12
        hour > 12 -> hour - 12
        else -> hour
    }
    val suffix = if (hour < 12) "AM" else "PM"
    return String.format(Locale.US, "%d:%02d %s", displayHour, minute, suffix)
}
