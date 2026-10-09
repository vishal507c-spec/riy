package com.vishal.riy.tracker

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.TimeZone

/** One day as the calendar needs it. */
data class TrackerCalendarDay(
    val epochDay: Long,
    val status: TrackerStatus?,
    /** True when this day is today and therefore still in progress. */
    val isToday: Boolean,
)

/** Everything the tracker screen renders. Derived, never invented. */
data class TrackerUiState(
    val stats: TrackerStats = TrackerStats.EMPTY,
    /** Currently displayed month, as an epoch day inside that month. */
    val monthAnchor: Long = 0L,
    val days: List<TrackerCalendarDay> = emptyList(),
    /** Blank cells before the 1st so the grid starts on Sunday. */
    val leadingBlanks: Int = 0,
    /** Non-null while a record is being created or edited. */
    val editingDate: Long? = null,
    val showCheckIn: Boolean = false,
    val checkInDate: Long? = null,
    val savedConfirmation: String? = null,
) {
    val isEmpty: Boolean get() = stats.recordedDays == 0 && stats.trackingStartDay == null
}

/**
 * Tracker state holder.
 *
 * It reads and writes through the EXISTING [TrackerStore], which in turn fires
 * the EXISTING `DriveSync.requestBackup()`. There is no second persistence
 * layer and no second cloud path here by design.
 */
class TrackerViewModel(app: Application) : AndroidViewModel(app) {

    private val store = TrackerStore(app)

    private val _state = MutableStateFlow(initialState())
    val state: StateFlow<TrackerUiState> = _state.asStateFlow()

    private fun initialState(): TrackerUiState {
        val now = System.currentTimeMillis()
        val today = TrackerDates.today(now, TimeZone.getDefault())
        return TrackerUiState(monthAnchor = today)
    }

    private fun refresh(
        editingDate: Long? = _state.value.editingDate,
        showCheckIn: Boolean = _state.value.showCheckIn,
        checkInDate: Long? = _state.value.checkInDate,
        confirmation: String? = null,
    ) {
        val now = System.currentTimeMillis()
        val tz = TimeZone.getDefault()
        val today = TrackerDates.today(now, tz)
        val records = store.all()
        val stats = TrackerCalculator.compute(records, today)
        val anchor = _state.value.monthAnchor.takeIf { it != 0L } ?: today
        val monthStart = TrackerDates.firstOfMonth(anchor)
        val monthLength = TrackerDates.lengthOfMonth(anchor)
        val byDay = records.associateBy { it.epochDay }

        _state.value = TrackerUiState(
            stats = stats,
            monthAnchor = monthStart,
            days = (0 until monthLength).map { offset ->
                val day = monthStart + offset
                TrackerCalendarDay(
                    epochDay = day,
                    status = byDay[day]?.status,
                    isToday = day == today,
                )
            },
            leadingBlanks = TrackerDates.dayOfWeek(monthStart, tz) - 1,
            editingDate = editingDate,
            showCheckIn = showCheckIn,
            checkInDate = checkInDate,
            savedConfirmation = confirmation,
        )
    }

    /**
     * Opens the morning check-in if one is pending, exactly once per day.
     *
     * This is the "first app use of the morning" path. It is an IN-APP screen,
     * presented only while the app is already in the foreground — never an
     * attempt to take over the lock screen.
     */
    fun onMorningForeground() {
        val now = System.currentTimeMillis()
        val today = TrackerDates.epochDayOf(now, TimeZone.getDefault())
        if (store.wasPromptedOn(today)) return
        val pending = TrackerCheckIn.pendingDate(today) { store.hasRecord(it) } ?: return
        if (!TrackerCheckIn.shouldAutoPrompt(pending, alreadyPromptedToday = true)) return
        store.markPrompted(today)
        openCheckIn(pending)
    }

    /** Opens the check-in for [epochDay] without the once-a-day guard. */
    fun openCheckIn(epochDay: Long) {
        refresh(showCheckIn = true, checkInDate = epochDay)
    }

    fun dismissCheckIn() {
        refresh(showCheckIn = false, checkInDate = null)
    }

    /** Whether a record already exists for [epochDay]; used by the routing layer. */
    fun hasRecord(epochDay: Long): Boolean = store.hasRecord(epochDay)

    /** Opens [epochDay] for editing from the calendar; no record = create. */
    fun editDay(epochDay: Long) = refresh(editingDate = epochDay)

    fun dismissEditor() = refresh(editingDate = null)

    /**
     * Saves the answer for the date that is currently open.
     *
     * [epochDay] is passed explicitly and is the date that gets written — it is
     * never re-derived from the clock, so yesterday's answer cannot land on
     * today. The record is written synchronously to local storage first, which
     * is what makes the save work with no internet at all.
     */
    fun save(epochDay: Long, status: TrackerStatus): Boolean {
        return runCatching {
            val now = System.currentTimeMillis()
            store.save(epochDay, status, now)
            // Local write is done; Drive backup was requested by the store and
            // queues itself if offline. Success of the CLOUD upload is never
            // claimed here — only the local save.
            TrackerNotifications.cancel(getApplication())
            refresh(
                editingDate = null,
                showCheckIn = false,
                checkInDate = null,
                confirmation = "Saved",
            )
            true
        }.getOrElse { false }
    }

    fun delete(epochDay: Long) {
        runCatching {
            store.delete(epochDay)
            refresh(editingDate = null)
        }
    }

    fun previousMonth() {
        val current = _state.value.monthAnchor
        _state.value = _state.value.copy(monthAnchor = TrackerDates.addMonthsClamped(current, -1))
        refresh()
    }

    fun nextMonth() {
        val current = _state.value.monthAnchor
        _state.value = _state.value.copy(monthAnchor = TrackerDates.addMonthsClamped(current, 1))
        refresh()
    }

    fun clearConfirmation() {
        _state.value = _state.value.copy(savedConfirmation = null)
    }

    /** Re-reads from disk; called on every resume so external changes appear. */
    fun reload() {
        val keepEditing = _state.value.editingDate
        val keepCheckIn = _state.value.showCheckIn
        val keepCheckInDate = _state.value.checkInDate
        _state.value = _state.value.copy(
            editingDate = null,
            showCheckIn = false,
            checkInDate = null,
        )
        refresh(keepEditing, keepCheckIn, keepCheckInDate)
    }
}