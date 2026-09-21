package com.vishal.riy.ui

import android.app.Application
import android.content.Context
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.vishal.riy.blocker.BlockerState
import com.vishal.riy.blocker.BlockerStateStore
import com.vishal.riy.blocker.BlockerVpnService
import com.vishal.riy.protection.ui.DefaultProtectionStateBridge
import com.vishal.riy.protection.ui.ProtectionUiState
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * THE one Android ViewModel the Compose UI observes. It owns a single
 * [ProtectionStateBridge] — the backend → UI translator — and a DISPLAY-ONLY
 * ticker that keeps the published snapshot live.
 *
 * WHAT THIS CLASS IS ALLOWED TO DO (and nothing else):
 *  - hold the bridge and republish its [ProtectionUiState];
 *  - refresh that snapshot on a display cadence so the countdown, the
 *    enforcement status and the recovery state stay current;
 *  - request protection START, and only after the system VPN consent dialog
 *    grants it.
 *
 * WHAT IT MUST NEVER DO:
 *  - decide NORMAL / RESTRICTED / HARDENED — that stays with the policy engine,
 *    reached only through the bridge;
 *  - compute any deadline (`now + duration`) or start any timer. The ticker
 *    below only RE-READS the authoritative LockEngine deadline via the bridge;
 *    `remaining = authoritativeExpiry - currentTime` happens there, nowhere
 *    else, and the value can never be armed, extended or shortened here;
 *  - call DevicePolicyManager, the enforcement engine, the restricted-mode
 *    controller or any store's write method. It holds no reference to any of
 *    them, so the UI cannot reach them even indirectly;
 *  - expose disable / pause / bypass / reset. No such function exists here.
 *
 * The refresh cadence is deliberately modest: nothing the user needs to see
 * changes faster than the enforcement read-back and the wall-clock countdown,
 * and a slower cadence keeps the UI's own work off the main thread.
 */
class ProtectionViewModel(application: Application) : AndroidViewModel(application) {

    private val stateStore = BlockerStateStore(application)

    private val bridge: DefaultProtectionStateBridge =
        DefaultProtectionStateBridge.forContext(application)

    /**
     * The single, authoritative UI state. Every value the UI renders comes from
     * here and from nowhere else.
     */
    val state: StateFlow<ProtectionUiState> = bridge.state

    init {
        // DISPLAY TICKER — refreshes the snapshot the bridge already computed.
        // This creates no deadline and owns no state: it only re-reads the real
        // backend sources so a countdown ticks and a state transition lands.
        viewModelScope.launch {
            while (isActive) {
                bridge.refresh()
                delay(REFRESH_MS)
            }
        }
    }

    /**
     * Requests protection start. Call ONLY after VPN consent is granted
     * (see the Activity result launcher in the UI). This is the ONE action the
     * UI may take; it starts the filtering service and records intent — it
     * never touches protection state, the deadline or enforcement.
     */
    fun enableProtection(context: Context) {
        stateStore.setProtectionWanted(true)
        BlockerVpnService.start(context)
    }

    private companion object {
        // ~2x/second: smooth enough for a seconds-resolution countdown and for
        // enforcement/recovery status to settle promptly, without busy-looping.
        const val REFRESH_MS = 500L
    }
}
