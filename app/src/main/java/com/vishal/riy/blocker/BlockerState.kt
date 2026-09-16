package com.vishal.riy.blocker

import java.util.concurrent.atomic.AtomicReference

/**
 * Single source of truth for the REAL protection state. The VPN service
 * reports transitions through [update]; the Compose UI observes [Snapshot].
 * The UI never sets the phase directly, so a fake "Protection ON" is
 * impossible.
 */
object BlockerState {

    enum class Phase {
        /** Protection has never been enabled (or the user turned it off). */
        OFF,

        /** VPN establishment in progress (permission granted, handshake running). */
        CONNECTING,

        /** tun interface is up and DNS filtering is running. */
        CONNECTED,

        /** Service failed (VPN revoked / permission missing); protection is NOT active. */
        FAILED,
    }

    data class Snapshot(
        val phase: Phase = Phase.OFF,
        val failureReason: String? = null,
    )

    private val snapshot = AtomicReference(Snapshot())

    /** Current real state; always derived from the service, never the UI. */
    fun current(): Snapshot = snapshot.get()

    fun update(phase: Phase, failureReason: String? = null) {
        snapshot.set(Snapshot(phase, failureReason))
    }
}
