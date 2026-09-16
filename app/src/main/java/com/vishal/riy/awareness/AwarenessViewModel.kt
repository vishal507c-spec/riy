package com.vishal.riy.awareness

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
 * Thin Android wrapper around [AwarenessController]: it owns a wall-clock
 * ticker that keeps the countdown and the pause progression live, and exposes
 * an immutable [AwarenessSnapshot] for Compose to render.
 *
 * All rules live in the controller (pure Kotlin, unit-tested); this class
 * never makes a protection decision itself.
 */
class AwarenessViewModel(application: Application) : AndroidViewModel(application) {

    private val controller = AwarenessController(
        store = PrefsAwarenessLockStore(application),
    )

    private val _state = MutableStateFlow(controller.snapshot())
    val state: StateFlow<AwarenessSnapshot> = _state.asStateFlow()

    init {
        viewModelScope.launch {
            // ~4x/second: smooth countdown without busy-loops.
            while (isActive) {
                controller.tick()
                _state.value = controller.snapshot()
                delay(TICK_MS)
            }
        }
    }

    /** Called by the browser layer when an adult search was blocked. */
    fun onAdultSearchDetected(url: String) {
        controller.onAdultSearchDetected(url)
        _state.value = controller.snapshot()
    }

    fun onTriggerSelected(trigger: Trigger) {
        controller.onTriggerSelected(trigger)
        _state.value = controller.snapshot()
    }

    fun onTriggerSkipped() {
        controller.onTriggerSkipped()
        _state.value = controller.snapshot()
    }

    private companion object {
        const val TICK_MS = 250L
    }
}
