package com.vishal.riy.lock

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Thin Android wrapper around [LockController]: it owns a wall-clock ticker
 * that keeps the countdown live and exposes an immutable [LockSnapshot] for
 * Compose to render. All rules live in the controller (pure Kotlin,
 * unit-tested); this class never makes a protection decision itself.
 */
class LockViewModel(application: Application) : AndroidViewModel(application) {

    private val controller = LockController(PrefsLockStore(application))

    private val _state = MutableStateFlow(controller.snapshot())
    val state: StateFlow<LockSnapshot> = _state.asStateFlow()

    init {
        viewModelScope.launch {
            // ~4x/second: a smoothly updating countdown without busy-loops.
            while (isActive) {
                controller.tick()
                _state.value = controller.snapshot()
                delay(TICK_MS)
            }
        }
    }

    private companion object {
        const val TICK_MS = 250L
    }
}
