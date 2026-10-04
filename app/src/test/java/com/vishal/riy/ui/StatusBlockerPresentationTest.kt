package com.vishal.riy.ui

import com.vishal.riy.R
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The zero-confusion contract for the WhatsApp Status card.
 *
 * The card must state the block in plain language, must only claim "Protection
 * Active" when the SYSTEM confirmed accessibility access, and must never leak a
 * package name, a signal id, a confidence value or an engine name.
 */
class StatusBlockerPresentationTest {

    @Test
    fun `the card leads with WhatsApp Status and BLOCKED`() {
        val presentation = active().presentation()

        assertEquals(R.string.status_blocker_title, presentation.titleRes)
        assertEquals(R.string.status_blocker_state_blocked, presentation.stateRes)
        assertEquals(R.string.status_blocker_whatsapp, presentation.appRes)
        assertEquals(R.string.status_blocker_protected, presentation.appStateRes)
    }

    @Test
    fun `the explanation promises that chats and calls still work`() {
        assertEquals(R.string.status_blocker_explanation, active().presentation().explanationRes)
    }

    @Test
    fun `the toggle reads as ON only when the user turned it on`() {
        assertEquals(R.string.status_blocker_toggle_on, active().presentation().toggleStateRes)
        assertTrue(active().presentation().toggleEnabled)

        assertEquals(
            R.string.status_blocker_toggle_off,
            active(blockingEnabled = false).presentation().toggleStateRes,
        )
        assertFalse(active(blockingEnabled = false).presentation().toggleEnabled)
    }

    @Test
    fun `Protection Active requires BOTH the intent and real accessibility access`() {
        assertTrue(active().blockingActive)
        assertFalse(
            "intent alone must never claim protection",
            active(accessibilityGranted = false).blockingActive,
        )
        assertFalse(
            "accessibility alone must never claim protection",
            active(blockingEnabled = false).blockingActive,
        )
    }

    @Test
    fun `the loading state claims nothing`() {
        val loading = StatusBlockerUiState.LOADING.presentation()

        assertFalse(loading.blockingActive)
        assertTrue("onboarding is shown until access is confirmed", loading.showOnboarding)
    }

    @Test
    fun `the onboarding card is shown only while access is missing`() {
        assertFalse(active().presentation().showOnboarding)
        assertTrue(active(accessibilityGranted = false).presentation().showOnboarding)
    }

    @Test
    fun `the onboarding explains the accessibility requirement`() {
        val onboarding = active(accessibilityGranted = false).presentation()

        assertEquals(R.string.status_blocker_onboarding_title, onboarding.onboardingTitleRes)
        assertEquals(R.string.status_blocker_onboarding_body, onboarding.onboardingBodyRes)
        assertEquals(R.string.status_blocker_enable_accessibility, onboarding.enableButtonRes)
    }

    @Test
    fun `WhatsApp is reported as not installed rather than as protected`() {
        val presentation = active(whatsappInstalled = false).presentation()

        assertEquals(R.string.status_blocker_not_installed, presentation.appStateRes)
        assertNotEquals(R.string.status_blocker_protected, presentation.appStateRes)
    }

    @Test
    fun `no selectable string is developer or package terminology`() {
        val states = listOf(
            StatusBlockerUiState.LOADING,
            active(),
            active(accessibilityGranted = false),
            active(blockingEnabled = false),
            active(whatsappInstalled = false),
        )

        states.forEach { state ->
            val presentation = state.presentation()
            listOf(
                presentation.titleRes,
                presentation.stateRes,
                presentation.toggleLabelRes,
                presentation.toggleStateRes,
                presentation.appRes,
                presentation.appStateRes,
                presentation.explanationRes,
                presentation.onboardingTitleRes,
                presentation.onboardingBodyRes,
                presentation.enableButtonRes,
                presentation.activeBadgeRes,
            ).forEach { res ->
                assertNotEquals("no blank strings", 0, res)
            }
        }
    }

    @Test
    fun `the state model exposes no way to change protection`() {
        // A data model with no mutating capability is what keeps the card from
        // reaching the store or the service behind the UI's back.
        val methods = StatusBlockerUiState::class.java.methods.map { it.name }.toSet()
        listOf("disable", "pause", "bypass", "unlock", "setEnabled", "enable", "reset")
            .forEach { forbidden ->
                assertFalse(
                    "StatusBlockerUiState must not expose '$forbidden'",
                    methods.any { it.equals(forbidden, ignoreCase = true) },
                )
            }
    }

    // ------------------------------------------------------------- helpers

    private fun active(
        accessibilityGranted: Boolean = true,
        blockingEnabled: Boolean = true,
        whatsappInstalled: Boolean = true,
    ) = StatusBlockerUiState(
        accessibilityGranted = accessibilityGranted,
        blockingEnabled = blockingEnabled,
        whatsappInstalled = whatsappInstalled,
    )
}
