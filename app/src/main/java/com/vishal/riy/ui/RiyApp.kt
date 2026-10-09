package com.vishal.riy.ui

import androidx.activity.compose.BackHandler
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel

/**
 * Root composable. Screens are chosen by the authoritative backend state, plus
 * an explicit user navigation choice for the Daily Tracker:
 *
 *  - a live restriction (RESTRICTED / HARDENED), or a recovery that must not be
 *    dismissed → [LockScreen] (full-screen, non-dismissible countdown). This
 *    outranks EVERYTHING: while locked the tracker is unreachable.
 *  - otherwise → [ProtectionScreen], [TrackerScreen] or the check-in sheet,
 *    whichever the user is on.
 *
 * While restricted the system Back gesture/key is consumed and cannot dismiss or
 * bypass the lock. There is no browser, no search UI — a restriction can only be
 * cleared by the backend's own deadline.
 *
 * [openCheckInOnStart] is how the morning notification deep-link arrives: the
 * Activity sets it and the tracker then asks about the pending date.
 *
 * This composable holds no security logic: it cannot decide a state, compute a
 * deadline, or reach any enforcement object. It only routes what the bridge
 * already decided.
 */
@Composable
fun RiyApp(openCheckInOnStart: Boolean = false) {
    RiyTheme {
        val viewModel: ProtectionViewModel = viewModel()
        val state by viewModel.state.collectAsStateWithLifecycle()
        val trackerViewModel: com.vishal.riy.tracker.TrackerViewModel = viewModel()
        val trackerState by trackerViewModel.state.collectAsStateWithLifecycle()

        // A locked user cannot back out of the lock screen. Recovery is equally
        // non-dismissible: the backend is mid-restore and must not be bypassed.
        val lockActive = state.isRestricted || state.isRecovering

        var screen by rememberSaveable { mutableStateOf(AppScreen.MAIN) }
        // The tracker must follow the lock, never race it.
        LaunchedEffect(lockActive) {
            if (lockActive) screen = AppScreen.MAIN
        }

        // Morning check-in: evaluated on the FIRST foreground of the day, and
        // only while the app is already visible. No background activity launch,
        // no full-screen intent, no attempt to take over the device lock.
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

        // Sci-fi klaxon on lockdown, deactivation chime on release. Sound only;
        // the restriction itself is still armed/cleared by the backend deadline.
        var wasLocked by rememberSaveable { mutableStateOf(false) }
        LaunchedEffect(lockActive) {
            if (lockActive && !wasLocked) SciFiSound.alarm()
            if (!lockActive && wasLocked) SciFiSound.release()
            wasLocked = lockActive
        }

        // While locked, Back is consumed. Otherwise Back walks back to MAIN.
        BackHandler(enabled = lockActive || screen != AppScreen.MAIN) {
            if (!lockActive) {
                trackerViewModel.dismissEditor()
                trackerViewModel.dismissCheckIn()
                screen = AppScreen.MAIN
            }
        }

        when {
            lockActive -> LockScreen(state = state)

            screen == AppScreen.CHECK_IN -> {
                val date = trackerState.editingDate
                if (date == null) {
                    screen = AppScreen.MAIN
                } else {
                    val existing = trackerState.days.firstOrNull { it.epochDay == date }?.status
                    TrackerCheckInSheet(
                        epochDay = date,
                        existingStatus = existing,
                        onSave = { day, status ->
                            trackerViewModel.save(day, status)
                            // Editing from the calendar returns there; the morning
                            // prompt returns to the main screen.
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
                        } else {
                            null
                        },
                    )
                }
            }

            screen == AppScreen.TRACKER -> TrackerScreen(
                state = trackerState,
                onBack = { screen = AppScreen.MAIN },
                onPreviousMonth = trackerViewModel::previousMonth,
                onNextMonth = trackerViewModel::nextMonth,
                onDayClick = { day ->
                    trackerViewModel.editDay(day)
                    screen = AppScreen.CHECK_IN
                },
                onConfirmationShown = trackerViewModel::clearConfirmation,
            )

            else -> ProtectionScreen(onOpenTracker = { screen = AppScreen.TRACKER })
        }
    }
}
