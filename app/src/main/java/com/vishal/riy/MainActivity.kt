package com.vishal.riy

import android.os.Bundle
import android.util.Log
import androidx.activity.compose.setContent
import androidx.appcompat.app.AppCompatActivity
import com.vishal.riy.blocker.BlockerState
import com.vishal.riy.blocker.BlockerStateStore
import com.vishal.riy.blocker.BlockerVpnService
import com.vishal.riy.ui.RiyApp

/**
 * App entry point. The whole UI is a single Compose screen ([RiyApp]) that
 * shows either the lock countdown or the protection status.
 */
class MainActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { RiyApp() }
        restoreProtectionIfInterrupted()
    }

    override fun onStart() {
        super.onStart()
        restoreProtectionIfInterrupted()
    }

    /**
     * Self-heal: if the user wants protection but the service is not running
     * (process death, force-stop, missed boot broadcast), restart it. This
     * never overrides a real OFF/FAILED reported by the service itself.
     */
    private fun restoreProtectionIfInterrupted() {
        val phase = BlockerState.current().phase
        if (BlockerStateStore(this).isProtectionWanted() &&
            phase != BlockerState.Phase.CONNECTED &&
            phase != BlockerState.Phase.CONNECTING
        ) {
            Log.i("BlockerApp", "protection wanted but $phase — restarting service")
            try {
                BlockerVpnService.start(this)
            } catch (e: Exception) {
                Log.e("BlockerApp", "failed to restart protection", e)
            }
        }
    }
}
