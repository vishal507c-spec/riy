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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme.colorScheme
import androidx.compose.material3.MaterialTheme.typography
import androidx.compose.material3.OutlinedButton
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.vishal.riy.R
import com.vishal.riy.tracker.TrackerCalendarDay
import com.vishal.riy.tracker.TrackerDates
import com.vishal.riy.tracker.TrackerDay
import com.vishal.riy.tracker.TrackerStatus
import com.vishal.riy.tracker.TrackerUiState
import java.text.DateFormatSymbols
import java.util.Locale
import java.util.TimeZone

/**
 * INNER FLAME — one question, one month, one history list.
 *
 * Layout order is fixed by the product spec:
 *   A. Daily check-in
 *   B. Monthly overview
 *   C. Activity calendar
 *   D. Recent history
 *   E. Settings
 *
 * No scores, no levels, no flame-history card, no trend charts, no bulk
 * actions and no duplicate controls. Every number is a count of real saved
 * dates.
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
    onDeleteRecord: (Long) -> Unit,
    onExportCsv: () -> Unit,
    onResetAll: () -> Unit,
    onConfirmationShown: () -> Unit = {},
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier
            .fillMaxSize()
            .background(Color.Black),
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = AppUI.ScreenPadding, vertical = 24.dp),
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

            FlameHeroCard(state = state)

            DailyCheckInCard(
                today = state.todayEpochDay,
                todayStatus = state.todayStatus,
                onSetToday = onSetToday,
            )

            MonthlyOverviewCard(state = state)

            ActivityCalendar(
                state = state,
                onPreviousMonth = onPreviousMonth,
                onNextMonth = onNextMonth,
                onDayClick = onDayClick,
            )

            RecentHistoryCard(
                history = state.history,
                onEdit = onDayClick,
                onDelete = onDeleteRecord,
            )

            SettingsCard(
                onExportCsv = onExportCsv,
                onResetAll = onResetAll,
            )
        }
    }
}

@Composable
private fun SavedBanner(onUndo: () -> Unit) {
    SectionCard(container = Color(0xFF141414)) {
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
 * The motivating hero: the current Inner Flame and the honest numbers behind
 * it. Every value comes from [TrackerUiState.stats], which is derived only from
 * real saved days — nothing is invented or faked.
 */
@Composable
private fun FlameHeroCard(state: TrackerUiState) {
    val stats = state.stats
    SectionCard(container = Color(0xFF141414)) {
        SectionLabel(stringResource(R.string.tracker_flame_title))
        Spacer(Modifier.height(8.dp))

        val headline = when {
            stats.streakFrozen -> stringResource(R.string.tracker_flame_frozen, stats.frozenStreak)
            stats.currentStreak > 0 -> stringResource(R.string.tracker_flame_burning, stats.currentStreak)
            state.todayStatus == TrackerStatus.NO -> stringResource(R.string.tracker_flame_saved_today)
            else -> stringResource(R.string.tracker_flame_waiting)
        }
        Text(
            text = headline,
            style = typography.headlineSmall,
            fontWeight = FontWeight.Bold,
            color = SciFiColors.SolarAmber,
        )

        val subline = when {
            stats.streakFrozen -> stringResource(R.string.tracker_flame_frozen_hint)
            stats.currentStreak == 0 && stats.longestStreak == 0 ->
                stringResource(R.string.tracker_flame_waiting_hint)
            else -> stats.trackingStartDay?.let {
                stringResource(R.string.tracker_flame_since, friendlyDate(it))
            }
        }
        if (subline != null) {
            Spacer(Modifier.height(2.dp))
            Text(
                text = subline,
                style = typography.bodySmall,
                color = colorScheme.onSurfaceVariant,
            )
        }

        Spacer(Modifier.height(14.dp))
        Row(modifier = Modifier.fillMaxWidth()) {
            HeroStat(
                value = if (stats.streakFrozen) stats.frozenStreak else stats.currentStreak,
                label = stringResource(R.string.tracker_stat_current_streak),
                accent = SciFiColors.NeonCyan,
                modifier = Modifier.weight(1f),
            )
            HeroStat(
                value = stats.longestStreak,
                label = stringResource(R.string.tracker_stat_longest_streak),
                accent = SciFiColors.SolarAmber,
                modifier = Modifier.weight(1f),
            )
            HeroStat(
                value = stats.noDays,
                label = stringResource(R.string.tracker_stat_no_days),
                accent = SciFiColors.GoGreen,
                modifier = Modifier.weight(1f),
            )
            HeroStat(
                value = stats.yesDays,
                label = stringResource(R.string.tracker_stat_yes_days),
                accent = colorScheme.error,
                modifier = Modifier.weight(1f),
            )
        }
    }
}

@Composable
private fun RowScope.HeroStat(
    value: Int,
    label: String,
    accent: Color,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier,
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

/** A. Daily check-in — the one thing the screen is for. */
@Composable
private fun DailyCheckInCard(
    today: Long,
    todayStatus: TrackerStatus?,
    onSetToday: (TrackerStatus?) -> Unit,
) {
    SectionCard(container = Color(0xFF141414)) {
        SectionLabel(stringResource(R.string.tracker_today_label))
        Spacer(Modifier.height(6.dp))
        Text(
            text = friendlyDate(today),
            style = typography.headlineSmall,
            fontWeight = FontWeight.Bold,
            color = SciFiColors.NeonCyan,
        )
        Spacer(Modifier.height(4.dp))
        Text(
            text = stringResource(R.string.tracker_today_question),
            style = typography.titleMedium,
            color = colorScheme.onSurface,
        )
        Spacer(Modifier.height(4.dp))
        Text(
            text = "Track one thing: did you have a leak today?",
            style = typography.bodySmall,
            color = colorScheme.onSurfaceVariant,
        )
        Text(
            text = "Leak = a slip. No Leak = a clean day. Not Recorded = no answer.",
            style = typography.bodySmall,
            color = colorScheme.onSurfaceVariant,
        )
        if (todayStatus != null) {
            Spacer(Modifier.height(4.dp))
            Text(
                text = stringResource(R.string.tracker_today_recorded, statusWord(todayStatus)),
                style = typography.bodyMedium,
                color = colorScheme.onSurfaceVariant,
            )
        }
        Spacer(Modifier.height(12.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            AnswerButton(
                label = stringResource(R.string.tracker_answer_yes),
                accent = SciFiColors.SolarAmber,
                selected = todayStatus == TrackerStatus.YES,
                onClick = { onSetToday(if (todayStatus == TrackerStatus.YES) null else TrackerStatus.YES) },
                modifier = Modifier.weight(1f),
            )
            AnswerButton(
                label = stringResource(R.string.tracker_answer_no),
                accent = SciFiColors.GoGreen,
                selected = todayStatus == TrackerStatus.NO,
                onClick = { onSetToday(if (todayStatus == TrackerStatus.NO) null else TrackerStatus.NO) },
                modifier = Modifier.weight(1f),
            )
        }
        Spacer(Modifier.height(8.dp))
        Text(
            text = stringResource(R.string.tracker_today_hint),
            style = typography.bodySmall,
            color = colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun AnswerButton(
    label: String,
    accent: Color,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier
            .height(52.dp)
            .background(
                if (selected) accent.copy(alpha = 0.22f) else Color(0xFF1A1A1A),
                AppUI.CardRadius,
            )
            .border(
                1.dp,
                if (selected) accent else colorScheme.onSurfaceVariant.copy(alpha = 0.25f),
                AppUI.CardRadius,
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

/** B. Monthly overview — three plain counts for the selected month. */
@Composable
private fun MonthlyOverviewCard(state: TrackerUiState) {
    val pastDays = state.days.filter { it.epochDay <= state.todayEpochDay }
    val noLeak = pastDays.count { it.status == TrackerStatus.NO }
    val leak = pastDays.count { it.status == TrackerStatus.YES }
    val notRecorded = pastDays.count { it.status == null }
    val recorded = noLeak + leak

    SectionCard(container = Color(0xFF141414)) {
        SectionLabel(stringResource(R.string.tracker_monthly_overview_title))
        Spacer(Modifier.height(6.dp))
        Text(
            text = monthLabel(state.monthAnchor),
            style = typography.titleLarge,
            fontWeight = FontWeight.Bold,
        )
        Spacer(Modifier.height(12.dp))
        Row(modifier = Modifier.fillMaxWidth()) {
            OverviewStat(noLeak, stringResource(R.string.tracker_monthly_no_leak), SciFiColors.GoGreen)
            OverviewStat(leak, stringResource(R.string.tracker_monthly_leak), SciFiColors.SolarAmber)
            OverviewStat(notRecorded, stringResource(R.string.tracker_monthly_not_recorded), colorScheme.onSurfaceVariant)
        }
        Spacer(Modifier.height(8.dp))
        Text(
            text = stringResource(R.string.tracker_monthly_total) + ": $recorded",
            style = typography.bodySmall,
            color = colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun RowScope.OverviewStat(value: Int, label: String, accent: Color) {
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

/** C. Activity calendar. */
@Composable
private fun ActivityCalendar(
    state: TrackerUiState,
    onPreviousMonth: () -> Unit,
    onNextMonth: () -> Unit,
    onDayClick: (Long) -> Unit,
) {
    SectionCard(container = Color(0xFF141414)) {
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
                        DayCell(
                            day = day,
                            today = state.todayEpochDay,
                            onClick = { onDayClick(day.epochDay) },
                        )
                    }
                }
            }
        }
        Spacer(Modifier.height(10.dp))
        StatusLegend()
    }
}

@Composable
private fun RowScope.DayCell(day: TrackerCalendarDay, today: Long, onClick: () -> Unit) {
    val future = day.epochDay > today
    val (tint, border) = when (day.status) {
        TrackerStatus.NO -> SciFiColors.GoGreen to SciFiColors.GoGreen
        TrackerStatus.YES -> SciFiColors.SolarAmber to SciFiColors.SolarAmber
        TrackerStatus.UNKNOWN -> colorScheme.onSurfaceVariant to colorScheme.onSurfaceVariant
        null -> Color(0xFF1A1A1A) to Color(0xFF2A2A2A)
    }
    val ring = if (day.isToday) SciFiColors.NeonCyan else border
    Box(
        modifier = Modifier
            .weight(1f)
            .aspectRatio(1f)
            .padding(3.dp)
            .background(tint.copy(alpha = if (day.status == null) 0.35f else 0.22f), CircleShape)
            .border(
                if (day.isToday) 2.dp else 1.dp,
                ring.copy(alpha = if (day.isToday) 1f else 0.7f),
                CircleShape,
            )
            .then(if (future) Modifier else Modifier.clickable(onClick = onClick)),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = dayOfMonthLabel(day.epochDay),
            style = typography.bodyMedium,
            color = if (future) colorScheme.onSurfaceVariant.copy(alpha = 0.4f)
            else if (day.status == null) colorScheme.onSurfaceVariant else colorScheme.onSurface,
            fontWeight = if (day.isToday) FontWeight.Bold else FontWeight.Normal,
        )
    }
}

@Composable
private fun StatusLegend() {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        LegendItem(SciFiColors.GoGreen, stringResource(R.string.tracker_legend_clean))
        LegendItem(SciFiColors.SolarAmber, stringResource(R.string.tracker_legend_slipped))
        LegendItem(colorScheme.onSurfaceVariant, stringResource(R.string.tracker_legend_not_sure))
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

/** D. Recent history — date, status, edit and delete. */
@Composable
private fun RecentHistoryCard(
    history: List<TrackerDay>,
    onEdit: (Long) -> Unit,
    onDelete: (Long) -> Unit,
) {
    var pendingDelete by remember { mutableStateOf<Long?>(null) }
    SectionCard(container = Color(0xFF141414)) {
        SectionLabel(stringResource(R.string.tracker_history_title))
        Spacer(Modifier.height(8.dp))
        if (history.isEmpty()) {
            Text(
                text = stringResource(R.string.tracker_history_empty),
                style = typography.bodySmall,
                color = colorScheme.onSurfaceVariant,
            )
        } else {
            history.forEach { record ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = friendlyDate(record.epochDay),
                            style = typography.bodyMedium,
                            color = colorScheme.onSurface,
                        )
                        if (!record.note.isNullOrBlank()) {
                            Text(
                                text = record.note,
                                style = typography.bodySmall,
                                color = colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                    Text(
                        text = statusWord(record.status),
                        style = typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                        color = statusColor(record.status),
                        modifier = Modifier.padding(horizontal = 8.dp),
                    )
                    TextButton(onClick = { onEdit(record.epochDay) }) {
                        Text(stringResource(R.string.tracker_history_edit), style = typography.labelSmall)
                    }
                    TextButton(onClick = { pendingDelete = record.epochDay }) {
                        Text(
                            text = stringResource(R.string.tracker_history_delete),
                            style = typography.labelSmall,
                            color = colorScheme.error,
                        )
                    }
                }
            }
        }
    }

    pendingDelete?.let { day ->
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            title = { Text(stringResource(R.string.tracker_history_delete_confirm)) },
            text = { Text(friendlyDate(day)) },
            confirmButton = {
                TextButton(onClick = {
                    pendingDelete = null
                    onDelete(day)
                }) { Text(stringResource(R.string.tracker_confirm)) }
            },
            dismissButton = {
                TextButton(onClick = { pendingDelete = null }) {
                    Text(stringResource(R.string.tracker_cancel))
                }
            },
        )
    }
}

/** E. Settings — privacy disclosure, export and the full fresh-start reset. */
@Composable
private fun SettingsCard(
    onExportCsv: () -> Unit,
    onResetAll: () -> Unit,
) {
    var pendingReset by remember { mutableStateOf(false) }

    SectionCard(container = Color(0xFF141414)) {
        SectionLabel(stringResource(R.string.tracker_privacy_title))
        Spacer(Modifier.height(6.dp))
        Text(
            text = stringResource(R.string.tracker_privacy_body),
            style = typography.bodySmall,
            color = colorScheme.onSurfaceVariant,
        )

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

        Spacer(Modifier.height(20.dp))
        SectionLabel(stringResource(R.string.tracker_reset_title))
        Spacer(Modifier.height(6.dp))
        Text(
            text = stringResource(R.string.tracker_reset_body),
            style = typography.bodySmall,
            color = colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(10.dp))
        OutlinedButton(
            onClick = { pendingReset = true },
            modifier = Modifier.fillMaxWidth().height(48.dp),
            shape = AppUI.CardRadius,
            colors = androidx.compose.material3.ButtonDefaults.outlinedButtonColors(
                contentColor = colorScheme.error,
            ),
        ) {
            Text(
                text = stringResource(R.string.tracker_reset_button),
                style = typography.titleSmall,
                color = colorScheme.error,
            )
        }
    }

    if (pendingReset) {
        AlertDialog(
            onDismissRequest = { pendingReset = false },
            title = { Text(stringResource(R.string.tracker_reset_title)) },
            text = { Text(stringResource(R.string.tracker_reset_confirm_body)) },
            confirmButton = {
                TextButton(onClick = {
                    pendingReset = false
                    onResetAll()
                }) { Text(stringResource(R.string.tracker_reset_confirm)) }
            },
            dismissButton = {
                TextButton(onClick = { pendingReset = false }) {
                    Text(stringResource(R.string.tracker_cancel))
                }
            },
        )
    }
}

@Composable
private fun statusWord(status: TrackerStatus): String = when (status) {
    TrackerStatus.NO -> stringResource(R.string.tracker_answer_no)
    TrackerStatus.YES -> stringResource(R.string.tracker_answer_yes)
    TrackerStatus.UNKNOWN -> stringResource(R.string.tracker_answer_unknown)
}

@Composable
private fun statusColor(status: TrackerStatus): Color = when (status) {
    TrackerStatus.NO -> SciFiColors.GoGreen
    TrackerStatus.YES -> SciFiColors.SolarAmber
    TrackerStatus.UNKNOWN -> colorScheme.onSurfaceVariant
}

private fun monthLabel(epochDay: Long): String {
    val (year, month, _) = TrackerDates.civilFromDays(epochDay)
    val name = DateFormatSymbols(Locale.US).getShortMonths().getOrNull(month - 1).orEmpty()
    return "$name $year".uppercase(Locale.US)
}

private fun friendlyDate(epochDay: Long): String {
    val (year, month, day) = TrackerDates.civilFromDays(epochDay)
    val name = DateFormatSymbols(Locale.US).getShortMonths().getOrNull(month - 1).orEmpty()
    return "$day $name $year"
}

private fun dayOfMonthLabel(epochDay: Long): String =
    TrackerDates.civilFromDays(epochDay).third.toString()

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