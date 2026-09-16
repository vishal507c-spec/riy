package com.vishal.riy.ui

import androidx.activity.compose.BackHandler
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.vishal.riy.lock.LockViewModel

/**
 * Root composable. There is exactly ONE screen:
 *  - while a lock is live -> [LockScreen] (full-screen, non-dismissible countdown)
 *  - otherwise            -> [ProtectionScreen] (status + enable)
 *
 * While locked the system Back gesture/key is consumed and cannot dismiss or
 * bypass the lock. There is no browser, no search UI and no navigation — the
 * lock can only be cleared by the deadline itself.
 */
@Composable
fun RiyApp() {
    RiyTheme {
        val lockViewModel: LockViewModel = viewModel()
        val lockState by lockViewModel.state.collectAsStateWithLifecycle()

        // A locked user cannot back out of the lock screen.
        BackHandler(enabled = lockState.locked) { /* intentionally consumed */ }

        if (lockState.locked) {
            LockScreen(remainingMillis = lockState.remainingMillis)
        } else {
            ProtectionScreen()
        }
    }
}
