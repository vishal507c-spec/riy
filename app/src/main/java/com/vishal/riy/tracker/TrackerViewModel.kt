package com.vishal.riy.tracker

import android.app.Application
import android.content.Intent
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
    /** Free-text note attached to this day, or null. */
    val note: String? = null,
) {
    /** True when a free-text note is attached to this day. */
    val hasNote: Boolean get() = note != null
}

/** Everything the tracker screen renders. Derived, never invented. */
data class TrackerUiState(
    val stats: TrackerStats = TrackerStats.EMPTY,
    /** The local calendar day "today", used to disable future dates. */
    val todayEpochDay: Long = 0L,
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
    /** Today's answer, or null while today is still unanswered. */
    val todayStatus: TrackerStatus? = null,
    /** All saved records, newest first, for the history list. */
    val history: List<TrackerDay> = emptyList(),
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

    init {
        // One-time: wipe all old tracker data and begin again from today.
        // Runs before any UI observes the state, so no stale history is ever
        // rendered. Guarded by a marker, so this happens only once.
        store.ensureFreshStart()
        refresh()
    }

    /** Undo window state for the short-lived "undo last save" action. */
    private var undoPending: Pair<Long, TrackerDay?>? = null
    private var undoExpiresAt = 0L

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
        val stats = TrackerCalculator.compute(
            records,
            today,
            store.streakFreeze,
            store.streakFreezeValue,
        )
        val anchor = _state.value.monthAnchor.takeIf { it != 0L } ?: today
        val monthStart = TrackerDates.firstOfMonth(anchor)
        val monthLength = TrackerDates.lengthOfMonth(anchor)
        val byDay = records.associateBy { it.epochDay }

        _state.value = TrackerUiState(
            stats = stats,
            todayEpochDay = today,
            monthAnchor = monthStart,
            days = (0 until monthLength).map { offset ->
                val day = monthStart + offset
                TrackerCalendarDay(
                    epochDay = day,
                    status = byDay[day]?.status,
                    isToday = day == today,
                    note = byDay[day]?.note,
                )
            },
            leadingBlanks = TrackerDates.dayOfWeek(monthStart, tz) - 1,
            editingDate = editingDate,
            showCheckIn = showCheckIn,
            checkInDate = checkInDate,
            savedConfirmation = confirmation,
            todayStatus = byDay[today]?.status,
            history = records.sortedByDescending { it.epochDay },
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
        // Line 101 already established that today had not been prompted, so
        // passing `true` here made this branch unreachable (the in-app check-in
        // never opened). Pass the real value.
        if (!TrackerCheckIn.shouldAutoPrompt(pending, alreadyPromptedToday = false)) return
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
    fun save(epochDay: Long, status: TrackerStatus, note: String? = null): Boolean {
        return runCatching {
            val now = System.currentTimeMillis()
            // Undo window: remember the pre-edit record so the user can
            // rewind the last save without touching anything else.
            undoPending = epochDay to store.find(epochDay)
            undoExpiresAt = now + UNDO_WINDOW_MS
            store.save(epochDay, status, now, note)
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

    /**
     * Rewinds the most recent [save] when it is still inside the short undo
     * window. Restoring a previously-absent day deletes it again; restoring
     * a previous status writes it back. Returns false when there is nothing
     * (or nothing recent) to undo.
     */
    fun undoLast(): Boolean {
        val pending = undoPending ?: return false
        if (System.currentTimeMillis() > undoExpiresAt) {
            undoPending = null
            return false
        }
        undoPending = null
        val (day, previous) = pending
        runCatching {
            if (previous == null) {
                store.delete(day)
            } else {
                store.save(day, previous.status, System.currentTimeMillis(), previous.note)
            }
        }.onSuccess {
            refresh()
            return true
        }
        return false
    }

    /** Sets today's answer directly from the Today card (null clears it). */
    fun setToday(status: TrackerStatus?) {
        val today = TrackerDates.today(System.currentTimeMillis(), TimeZone.getDefault())
        if (status == null) {
            runCatching { store.delete(today) }
        } else {
            save(today, status)
        }
        refresh()
    }

    /**
     * Feature: Streak freeze. Enabling keeps the dashboard's current run
     * visible; disabling resumes counting from real records.
     */
    fun setStreakFreeze(value: Boolean) {
        if (value) {
            val today = TrackerDates.today(System.currentTimeMillis(), TimeZone.getDefault())
            val current = TrackerCalculator.compute(store.all(), today).currentStreak
            store.streakFreezeValue = current
        }
        store.streakFreeze = value
        refresh()
    }

    /**
     * Feature: Daily note. Attaches or clears a free-text note on a day.
     * Notes never touch the status or the counts.
     */
    fun note(epochDay: Long, text: String?) {
        runCatching {
            store.saveNote(epochDay, text, System.currentTimeMillis())
        }.onSuccess { refresh() }
    }

    /**
     * Feature: Mark every day of the displayed month with [status]
     * (used by the "Mark all of this month" bulk action).
     */
    fun bulkMarkMonth(status: TrackerStatus, monthAnchor: Long = _state.value.monthAnchor) {
        runCatching {
            val days = monthDays(monthAnchor)
            store.bulkSave(days, status, System.currentTimeMillis())
            refresh()
        }.onSuccess {
            undoPending = null
        }
    }

    /**
     * Feature: Clear every record of the displayed month (bulk delete).
     * Returns how many days were removed; -1 when the month had none.
     */
    fun clearMonth(monthAnchor: Long = _state.value.monthAnchor): Int {
        val deleted = runCatching { store.clearMonth(TrackerDates.firstOfMonth(monthAnchor), TrackerDates.lengthOfMonth(monthAnchor)) }.getOrDefault(-1)
        if (deleted > 0) {
            undoPending = null
            refresh()
        }
        return deleted
    }

    /**
     * FULL RESET — wipes every tracker record and setting, then rebuilds the
     * state from scratch so tracking begins with TODAY as a blank slate.
     *
     * This is the "start fresh" action: it drops the undo window, the saved
     * banner and the old month anchor, so no stale UI survives the wipe and
     * the calendar lands on the current month immediately.
     */
    fun resetAll(): Boolean = runCatching {
        store.clearAll()
        undoPending = null
        undoExpiresAt = 0L
        _state.value = _state.value.copy(
            monthAnchor = 0L,
            editingDate = null,
            showCheckIn = false,
            checkInDate = null,
            savedConfirmation = null,
        )
        refresh()
    }.isSuccess

    /**
     * Reminder settings, mirrored for the tracker screen. Re-arms the
     * existing alarm when enabled.
     */
    var reminderEnabled: Boolean
        get() = store.reminderEnabled
        set(value) {
            store.reminderEnabled = value
            TrackerReminder.rescheduleFromPreferences(getApplication())
        }

    var reminderHour: Int
        get() = store.reminderHour
        set(value) {
            store.reminderHour = value
            TrackerReminder.rescheduleFromPreferences(getApplication())
        }

    var reminderMinute: Int
        get() = store.reminderMinute
        set(value) {
            store.reminderMinute = value
            TrackerReminder.rescheduleFromPreferences(getApplication())
        }

    /**
     * Trend data for the monthly trend chart: statuses for the last [days]
     * calendar days, oldest first, chronologically ordered.
     */
    fun trendData(days: Int = 30): List<TrackerStatus?> {
        val today = TrackerDates.today(System.currentTimeMillis(), TimeZone.getDefault())
        val byDay = store.all().associateBy { it.epochDay }
        return (0 until days).map { today - it }.reversed().map { byDay[it]?.status }
    }

    /**
     * Feature: Export CSV. Shares the full history as CSV through the system
     * share sheet. Nothing is written to shared storage and nothing leaves the
     * device unless the user picks an app that receives it.
     */
    fun exportCsv(): Boolean = runCatching {
        val app = getApplication<Application>()
        val records = store.all().sortedBy { it.epochDay }
        val csv = buildString {
            append("epochDay,status,createdAt,updatedAt,note\n")
            records.forEach { record ->
                append(record.epochDay).append(',')
                append(record.status.name).append(',')
                append(record.createdAt).append(',')
                append(record.updatedAt).append(',')
                append(record.note.orEmpty().replace(",", " ").replace("\n", " "))
                append('\n')
            }
        }
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "text/csv"
            putExtra(Intent.EXTRA_TEXT, csv)
        }
        val chooser = Intent.createChooser(send, "RIY — tracker export")
        chooser.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        app.startActivity(chooser)
        true
    }.getOrElse { false }

    private fun monthDays(monthAnchor: Long): List<Long> {
        val first = TrackerDates.firstOfMonth(monthAnchor)
        return (0 until TrackerDates.lengthOfMonth(monthAnchor)).map { first + it }
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

    private companion object {
        const val UNDO_WINDOW_MS = 5_000L
    }
}