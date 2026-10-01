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
 * Root composable. Exactly ONE screen, chosen solely by the authoritative
 * [com.vishal.riy.protection.ui.ProtectionUiState] the backend publishes:
 *
 *  - a live restriction (RESTRICTED / HARDENED), or a recovery that must not be
 *    dismissed → [LockScreen] (full-screen, non-dismissible countdown);
 *  - otherwise → [ProtectionScreen] (status + enable).
 *
 * While restricted the system Back gesture/key is consumed and cannot dismiss or
 * bypass the lock. There is no browser, no search UI and no navigation — a
 * restriction can only be cleared by the backend's own deadline.
 *
 * This composable holds no security logic: it cannot decide a state, compute a
 * deadline, or reach any enforcement object. It only routes what the bridge
 * already decided.
 */
@Composable
fun RiyApp() {
    RiyTheme {
        val viewModel: ProtectionViewModel = viewModel()
        val state by viewModel.state.collectAsStateWithLifecycle()

        // A locked user cannot back out of the lock screen. Recovery is equally
        // non-dismissible: the backend is mid-restore and must not be bypassed.
        val lockActive = state.isRestricted || state.isRecovering

        // Sci-fi klaxon on lockdown, deactivation chime on release. Sound only;
        // the restriction itself is still armed/cleared by the backend deadline.
        var wasLocked by rememberSaveable { mutableStateOf(false) }
        LaunchedEffect(lockActive) {
            if (lockActive && !wasLocked) SciFiSound.alarm()
            if (!lockActive && wasLocked) SciFiSound.release()
            wasLocked = lockActive
        }

        BackHandler(enabled = lockActive) { /* intentionally consumed */ }

        if (lockActive) {
            LockScreen(state = state)
        } else {
            ProtectionScreen()
        }
    }
}
