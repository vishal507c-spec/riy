package com.vishal.riy.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material.icons.filled.Timer
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel

/**
 * Root composable. The backend state always wins: a live restriction or a
 * recovery that must not be dismissed shows [LockScreen] full-screen, above
 * navigation. Otherwise the user moves between Shield and Tracker with a
 * standard bottom navigation bar.
 */
@Composable
fun RiyApp(openCheckInOnStart: Boolean = false) {
    RiyTheme {
        val viewModel: ProtectionViewModel = viewModel()
        val state by viewModel.state.collectAsStateWithLifecycle()
        val trackerViewModel: com.vishal.riy.tracker.TrackerViewModel = viewModel()
        val trackerState by trackerViewModel.state.collectAsStateWithLifecycle()

        val lockActive = state.isRestricted || state.isRecovering

        var screen by rememberSaveable { mutableStateOf(AppScreen.MAIN) }
        LaunchedEffect(lockActive) {
            if (lockActive) screen = AppScreen.MAIN
        }

        LaunchedEffect(openCheckInOnStart, lockActive) {
            if (lockActive) return@LaunchedEffect
            if (openCheckInOnStart) {
                val today = com.vishal.riy.tracker.TrackerDates.today(
                    System.currentTimeMillis(),
                    java.util.TimeZone.getDefault(),
                )
                val pending = com.vishal.riy.tracker.TrackerCheckIn.pendingDate(today) { day ->
                    trackerViewModel.hasRecord(day)
                }
                if (pending != null) {
                    trackerViewModel.editDay(pending)
                    screen = AppScreen.CHECK_IN
                }
            } else {
                trackerViewModel.onMorningForeground()
                if (trackerViewModel.state.value.editingDate != null) {
                    screen = AppScreen.CHECK_IN
                }
            }
        }

        var wasLocked by rememberSaveable { mutableStateOf(false) }
        LaunchedEffect(lockActive) {
            if (lockActive && !wasLocked) SciFiSound.alarm()
            if (!lockActive && wasLocked) SciFiSound.release()
            wasLocked = lockActive
        }

        BackHandler(enabled = lockActive || screen != AppScreen.MAIN) {
            if (!lockActive) {
                trackerViewModel.dismissEditor()
                trackerViewModel.dismissCheckIn()
                screen = AppScreen.MAIN
            }
        }

        if (lockActive) {
            LockScreen(state = state)
            return@RiyTheme
        }

        Scaffold(
            bottomBar = {
                if (screen != AppScreen.CHECK_IN) {
                    NavigationBar {
                        NavigationBarItem(
                            selected = screen == AppScreen.MAIN,
                            onClick = { screen = AppScreen.MAIN },
                            icon = { Icon(Icons.Filled.Shield, contentDescription = null) },
                            label = { Text("Shield") },
                        )
                        NavigationBarItem(
                            selected = screen == AppScreen.TRACKER,
                            onClick = { screen = AppScreen.TRACKER },
                            icon = { Icon(Icons.Filled.Timer, contentDescription = null) },
                            label = { Text("Inner Flame") },
                        )
                    }
                }
            },
        ) { padding ->
            Box(Modifier.padding(padding)) {
                when (screen) {
                    AppScreen.CHECK_IN -> {
                        val date = trackerState.editingDate
                        if (date == null) {
                            screen = AppScreen.MAIN
                        } else {
                            val existing = trackerState.days.firstOrNull { it.epochDay == date }
                            TrackerCheckInSheet(
                                epochDay = date,
                                existingStatus = existing?.status,
                                existingNote = existing?.note,
                                onSave = { day, status, note ->
                                    trackerViewModel.save(day, status, note)
                                    screen = if (existing != null) AppScreen.TRACKER else AppScreen.MAIN
                                },
                                onDismiss = {
                                    trackerViewModel.dismissEditor()
                                    screen = AppScreen.MAIN
                                },
                                onDelete = if (existing != null) {
                                    {
                                        trackerViewModel.delete(date)
                                        screen = AppScreen.TRACKER
                                    }
                                } else null,
                            )
                        }
                    }

                    AppScreen.TRACKER -> TrackerScreen(
                        state = trackerState,
                        onBack = { screen = AppScreen.MAIN },
                        onPreviousMonth = trackerViewModel::previousMonth,
                        onNextMonth = trackerViewModel::nextMonth,
                        onDayClick = { day ->
                            trackerViewModel.editDay(day)
                            screen = AppScreen.CHECK_IN
                        },
                        onSetToday = trackerViewModel::setToday,
                        onUndo = { trackerViewModel.undoLast() },
                        onSetStreakFreeze = trackerViewModel::setStreakFreeze,
                        onBulkMark = { status -> trackerViewModel.bulkMarkMonth(status) },
                        onClearMonth = { trackerViewModel.clearMonth() },
                        onExportCsv = { trackerViewModel.exportCsv() },
                        reminderEnabled = trackerViewModel.reminderEnabled,
                        onReminderEnabled = { trackerViewModel.reminderEnabled = it },
                        reminderHour = trackerViewModel.reminderHour,
                        onReminderHour = { trackerViewModel.reminderHour = it },
                        reminderMinute = trackerViewModel.reminderMinute,
                        onReminderMinute = { trackerViewModel.reminderMinute = it },
                        onConfirmationShown = trackerViewModel::clearConfirmation,
                    )

                    else -> ProtectionScreen(onOpenTracker = { screen = AppScreen.TRACKER })
                }
            }
        }
    }
}
