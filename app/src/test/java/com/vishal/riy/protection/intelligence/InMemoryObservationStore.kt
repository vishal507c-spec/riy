package com.vishal.riy.protection.intelligence

/**
 * In-memory [ObservationStore] for unit tests. A new intelligence layer reading
 * the SAME instance is exactly what a fresh process does with the persisted
 * store after an app restart, a force-stop or a device reboot — which is how
 * those guarantees are tested without a device.
 *
 * Mirrors [com.vishal.riy.lock.InMemoryLockStore],
 * [com.vishal.riy.protection.state.InMemoryProtectionStateStore] and
 * [com.vishal.riy.protection.escalation.InMemoryEscalationStore] on purpose:
 * one test-double style for every store contract.
 */
class InMemoryObservationStore(
    private val clock: () -> Long = System::currentTimeMillis,
) : ObservationStore {

    @Volatile
    private var observations: MutableList<SignalObservation> = mutableListOf()

    override fun load(): List<SignalObservation> {
        val now = clock()
        val retained = observations
            .filter { now - it.timestamp <= ProtectionCorrelationWindow.CORRELATION_WINDOW_MS }
            .takeLast(ProtectionCorrelationWindow.MAX_RETAINED)
        if (retained.size < observations.size) observations = retained.toMutableList()
        return retained.toList()
    }

    override fun append(observation: SignalObservation): Boolean {
        val current = load()
        if (current.any { existing ->
                existing.domain == observation.domain &&
                    existing.matchClass == observation.matchClass &&
                    observation.timestamp - existing.timestamp <
                    ProtectionCorrelationWindow.SAME_DOMAIN_REPEAT_MS
            }
        ) {
            return false
        }
        observations = (current + observation).toMutableList()
        return true
    }

    override fun clear() {
        observations = mutableListOf()
    }
}
