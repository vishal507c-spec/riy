package com.vishal.riy.protection.ui

import kotlinx.coroutines.flow.StateFlow

/**
 * The one bridge between the protection backend and the Compose UI.
 *
 * It COMBINES real backend sources — the protection state store, the risk and
 * policy engines' latest decision, the integrity engine's status, the event
 * store, the app policy resolver and the lock engine's authoritative deadline —
 * into a single [ProtectionUiState] that the UI observes.
 *
 * DEPENDENCY DIRECTION (enforced by design):
 *
 *     Backend (state/integrity/events/lock deadline)
 *         →  ProtectionStateBridge
 *             →  Compose UI
 *
 * The bridge is read-only with respect to security: it exposes [state] and
 * nothing that can transition protection. The UI subscribes; it never becomes a
 * security authority.
 *
 * The production implementation is [DefaultProtectionStateBridge], built by
 * [DefaultProtectionStateBridge.Companion.forContext].
 */
interface ProtectionStateBridge {

    /** The current, immutable UI state. Updated by the backend, read by the UI. */
    val state: StateFlow<ProtectionUiState>
}
