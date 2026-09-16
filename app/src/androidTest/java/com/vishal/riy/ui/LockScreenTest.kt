package com.vishal.riy.ui

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import com.vishal.riy.MainActivity
import com.vishal.riy.lock.LockEngine
import com.vishal.riy.lock.LockState
import com.vishal.riy.lock.PrefsLockStore
import org.junit.Assert.assertFalse
import org.junit.Rule
import org.junit.Test

/**
 * Instrumented tests for the one thing that cannot be proven on the JVM: that
 * the lock screen is what the user sees while a lock is live and that neither
 * the Back key nor simply closing the UI dismisses it before the deadline.
 *
 * The lock is armed exactly the way the VPN service arms it — a 2-hour
 * deadline written to the persisted store — so these tests exercise the real
 * survival path rather than a test-only hook. (The countdown math itself —
 * exactly 2h, persistence, ticking down to zero — is unit-tested in
 * LockEngineTest / LockSurvivalTest.)
 */
class LockScreenTest {

    @get:Rule
    val composeRule = createAndroidComposeRule<MainActivity>()

    private fun armLock() {
        val now = System.currentTimeMillis()
        PrefsLockStore(composeRule.activity).saveState(
            LockEngine.onPornDetected(LockState.EMPTY, now, "pornhub.com"),
        )
    }

    private fun waitForLockScreen() {
        composeRule.waitUntil(TIMEOUT_MS) {
            composeRule.onAllNodesWithText("Porn search detected.").fetchSemanticsNodes().isNotEmpty()
        }
    }

    @Test
    fun lockedDeviceShowsLockScreenWithReason() {
        armLock()
        waitForLockScreen()

        composeRule.onNodeWithText("Protection Active").assertIsDisplayed()
        composeRule.onNodeWithText("Porn search detected.").assertIsDisplayed()
        composeRule.onNodeWithText("Use this time to step away from the urge.").assertIsDisplayed()
    }

    @Test
    fun backKeyCannotBypassTheLock() {
        armLock()
        waitForLockScreen()

        // The Back gesture/key is consumed by the app and changes nothing.
        // (The system routes the hardware Back key through this same dispatcher.)
        composeRule.activity.runOnUiThread {
            composeRule.activity.onBackPressedDispatcher.onBackPressed()
        }
        composeRule.waitForIdle()

        composeRule.onNodeWithText("Porn search detected.").assertIsDisplayed()
        composeRule.onNodeWithText("Protection Active").assertIsDisplayed()
        // The activity was not finished — there is no way out.
        assertFalse("Back must not finish (bypass) the lock", composeRule.activity.isFinishing)
    }

    @Test
    fun unlockedDeviceShowsTheProtectionScreenInstead() {
        // No lock armed: the normal minimal UI is shown, not the lock screen.
        composeRule.waitUntil(TIMEOUT_MS) {
            composeRule.onAllNodesWithText("Enable Protection").fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithText("Enable Protection").assertIsDisplayed()
    }

    private companion object {
        const val TIMEOUT_MS = 10_000L
    }
}
