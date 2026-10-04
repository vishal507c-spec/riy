package com.vishal.riy.ui

import android.content.Context
import com.vishal.riy.R
import com.vishal.riy.guard.StatusBlockerService
import com.vishal.riy.guard.UiGuardStore
import com.vishal.riy.guard.rules.WhatsAppStatusRule

/**
 * The single authoritative, READ-ONLY view the dashboard renders for the
 * WhatsApp Status guard. Pure Kotlin — no Compose, no Android — so every rule
 * below is plain JVM unit-testable.
 *
 * SECURITY RULE, exactly as for [com.vishal.riy.protection.ui.ProtectionUiState]:
 * it exposes FACTS only. It carries no method that could change a guard, so the
 * UI cannot reach the store or the service except through the one intent the
 * user explicitly toggles.
 *
 * @param accessibilityGranted the system's own read-back of accessibility access.
 * @param blockingEnabled       the user's stored choice for this target.
 * @param whatsappInstalled     whether official WhatsApp is on this device.
 */
data class StatusBlockerUiState(
    val accessibilityGranted: Boolean = false,
    val blockingEnabled: Boolean = false,
    val whatsappInstalled: Boolean = false,
) {

    /**
     * True only when the guard can ACT right now: the user's intent is on AND
     * the system confirms accessibility access. Never assumed, never inferred
     * from the toggle alone — an honest "not active" default, matching the rest
     * of the app's zero-confusion contract.
     */
    val blockingActive: Boolean
        get() = accessibilityGranted && blockingEnabled

    /** The onboarding card is shown exactly when access has not been granted. */
    val needsOnboarding: Boolean
        get() = !accessibilityGranted

    companion object {
        /** Safe pre-load state: nothing claimed. */
        val LOADING = StatusBlockerUiState()
    }
}

/** Reads the real store + the real system read-back. No caching, no guessing. */
fun readStatusBlockerState(context: Context): StatusBlockerUiState = StatusBlockerUiState(
    accessibilityGranted = StatusBlockerService.isAccessibilityEnabled(context),
    blockingEnabled = UiGuardStore(context).isEnabled(WhatsAppStatusRule.TARGET_ID),
    whatsappInstalled = StatusBlockerService.isWhatsAppInstalled(context),
)

/** Persists the user's single toggle choice for this target only. */
fun setStatusBlockingEnabled(context: Context, enabled: Boolean) {
    UiGuardStore(context).setEnabled(WhatsAppStatusRule.TARGET_ID, enabled)
}

/**
 * Everything the card renders, as a pure function of [StatusBlockerUiState].
 *
 * The zero-confusion contract holds here too: no package name, no confidence
 * value, no signal id and no engine name is ever selectable — only the plain
 * language the person actually reads.
 */
data class StatusBlockerPresentation(
    val titleRes: Int,
    val stateRes: Int,
    val toggleLabelRes: Int,
    val toggleStateRes: Int,
    val toggleEnabled: Boolean,
    val appRes: Int,
    val appStateRes: Int,
    val explanationRes: Int,
    val showOnboarding: Boolean,
    val onboardingTitleRes: Int,
    val onboardingBodyRes: Int,
    val enableButtonRes: Int,
    val activeBadgeRes: Int,
    val blockingActive: Boolean,
)

/** The single mapping. "Active" outranks everything; otherwise stay honest. */
fun StatusBlockerUiState.presentation(): StatusBlockerPresentation = StatusBlockerPresentation(
    titleRes = R.string.status_blocker_title,
    stateRes = R.string.status_blocker_state_blocked,
    toggleLabelRes = R.string.status_blocker_toggle_label,
    toggleStateRes = if (blockingEnabled) {
        R.string.status_blocker_toggle_on
    } else {
        R.string.status_blocker_toggle_off
    },
    toggleEnabled = blockingEnabled,
    appRes = R.string.status_blocker_whatsapp,
    appStateRes = if (whatsappInstalled) {
        R.string.status_blocker_protected
    } else {
        R.string.status_blocker_not_installed
    },
    explanationRes = R.string.status_blocker_explanation,
    showOnboarding = needsOnboarding,
    onboardingTitleRes = R.string.status_blocker_onboarding_title,
    onboardingBodyRes = R.string.status_blocker_onboarding_body,
    enableButtonRes = R.string.status_blocker_enable_accessibility,
    activeBadgeRes = R.string.status_blocker_active,
    blockingActive = blockingActive,
)
