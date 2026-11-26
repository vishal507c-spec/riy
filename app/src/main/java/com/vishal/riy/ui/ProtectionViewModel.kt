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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * THE one Android ViewModel the Compose UI observes. It owns a single
 * [DefaultProtectionStateBridge] and an action guard so a "Fix protection" tap
 * is acknowledged instantly and cannot start a second concurrent recovery.
 *
 * The engine stays authoritative: this class only REQUESTS work (start / real
 * recheck), never decides a protection state and never claims success. The
 * result shown is always read back from [BlockerState] through the bridge.
 */
class ProtectionViewModel(application: Application) : AndroidViewModel(application) {

    private val stateStore = BlockerStateStore(application)

    private val bridge: DefaultProtectionStateBridge =
        DefaultProtectionStateBridge.forContext(application)

    val state: StateFlow<ProtectionUiState> = bridge.state

    /**
     * Immediate UI acknowledgement of a button press. Backed by nothing but a
     * real in-flight service action; cleared only when the backend reports a
     * terminal phase (or a hard timeout), never on a timer that pretends work
     * finished.
     */
    private val _busy = MutableStateFlow(false)
    val isBusy: StateFlow<Boolean> = _busy.asStateFlow()

    private var actionStartedAt = 0L
    private var sawInProgress = false

    init {
        // A past verification is a fact; restore it so the dashboard can show
        // "last verified" even after the process was killed.
        BlockerState.restoreVerifiedAt(stateStore.lastVerifiedAt.takeIf { it > 0L })
        viewModelScope.launch {
            while (isActive) {
                // The backend reads (and its own persistence) must not run on
                // the main thread twice a second.
                withContext(Dispatchers.IO) { bridge.refresh() }
                settleBusyState()
                delay(REFRESH_MS)
            }
        }
    }

    /** Clears the CHECKING acknowledgement only when real work has settled. */
    private fun settleBusyState() {
        if (!_busy.value) return
        val phase = BlockerState.current().phase
        if (phase == BlockerState.Phase.CONNECTING ||
            phase == BlockerState.Phase.INITIALIZING
        ) {
            sawInProgress = true
        }
        val elapsed = System.currentTimeMillis() - actionStartedAt
        val terminal = phase == BlockerState.Phase.CONNECTED ||
            phase == BlockerState.Phase.FAILED ||
            phase == BlockerState.Phase.OFF
        if ((sawInProgress && terminal) || elapsed > ACTION_TIMEOUT_MS) {
            _busy.value = false
            sawInProgress = false
        }
    }

    /**
     * Requests protection start. Call ONLY after VPN consent is granted.
     */
    fun enableProtection(context: Context) = startAction(context, recheck = false)

    /**
     * The real "Fix protection" action. Unlike a plain start this forces a
     * teardown and a fresh end-to-end verification, so a stale "running" flag
     * can no longer turn the button into a no-op.
     */
    fun fixProtection(context: Context) = startAction(context, recheck = true)

    private fun startAction(context: Context, recheck: Boolean) {
        if (!_busy.compareAndSet(false, true)) return // already recovering
        actionStartedAt = System.currentTimeMillis()
        sawInProgress = false
        viewModelScope.launch(Dispatchers.IO) {
            val delivered = runCatching {
                stateStore.setProtectionWanted(true)
                com.vishal.riy.admin.AlwaysOnVpn.apply(context)
                com.vishal.riy.blocker.ProtectionWatchdog.schedule(context)
                if (recheck) {
                    BlockerVpnService.recheck(context)
                } else {
                    BlockerVpnService.start(context, allowRetry = false)
                    true
                }
            }.getOrDefault(false)
            if (!delivered) {
                reportFailure(
                    "RIY could not reach the protection service. Try again, and allow RIY to run in the background.",
                    BlockerState.FailureKind.VPN_DISCONNECTED,
                )
            }
        }
    }

    /** The system VPN consent dialog was dismissed or denied. */
    fun reportPermissionDenied() {
        reportFailure(
            "VPN permission was not granted. Tap Allow protection and accept the system prompt.",
            BlockerState.FailureKind.VPN_PERMISSION_MISSING,
        )
    }

    /** Surfaces a real, understandable failure instead of a silent dead tap. */
    private fun reportFailure(reason: String, kind: BlockerState.FailureKind) {
        _busy.value = false
        sawInProgress = false
        BlockerState.update(
            phase = BlockerState.Phase.FAILED,
            failureReason = reason,
            failure = kind,
        )
    }

    private companion object {
        /** 1s keeps a seconds-resolution countdown accurate without doing the
         * full backend read twice a second. */
        const val REFRESH_MS = 1_000L

        /** Hard upper bound so a ROM that refuses the service can never leave
         * the UI spinning forever. */
        const val ACTION_TIMEOUT_MS = 15_000L
    }
}
