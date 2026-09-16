package com.vishal.riy.ui

import android.app.Application
import android.content.Context
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.vishal.riy.blocker.BlockerState
import com.vishal.riy.blocker.BlockerStateStore
import com.vishal.riy.blocker.BlockerVpnService
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Bridges the REAL protection state ([BlockerState], owned by
 * [BlockerVpnService]) to the Compose UI. The UI can only request enabling —
 * it can never fabricate a status, because the phase always comes from the
 * service.
 */
class ProtectionViewModel(application: Application) : AndroidViewModel(application) {

    private val stateStore = BlockerStateStore(application)

    private val _state = MutableStateFlow(BlockerState.current())
    val state: StateFlow<BlockerState.Snapshot> = _state.asStateFlow()

    init {
        // Lightweight poll (500 ms): keeps status and failure reasons live
        // without any coupling between service and UI lifecycles.
        viewModelScope.launch {
            while (true) {
                _state.value = BlockerState.current()
                delay(500)
            }
        }
    }

    /**
     * Requests protection start. Call ONLY after VPN consent is granted
     * (see the Activity result launcher in the UI).
     */
    fun enableProtection(context: Context) {
        stateStore.setProtectionWanted(true)
        BlockerVpnService.start(context)
    }
}
