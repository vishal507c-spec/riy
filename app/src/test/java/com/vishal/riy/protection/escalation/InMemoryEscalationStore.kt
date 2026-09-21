package com.vishal.riy.protection.escalation

/**
 * In-memory [ProtectionEscalationStore] for unit tests. A new engine reading
 * the SAME instance is exactly what a fresh process does with persisted state
 * after an app restart, a force-stop or a device reboot — so those guarantees
 * are tested without a device.
 *
 * Mirrors [com.vishal.riy.protection.state.InMemoryProtectionStateStore] and
 * [com.vishal.riy.lock.InMemoryLockStore] on purpose: one test-double style
 * for every store contract.
 */
class InMemoryEscalationStore : ProtectionEscalationStore {

    @Volatile
    private var state: EscalationState = EscalationState.NONE

    override fun load(): EscalationState = state

    override fun save(state: EscalationState) {
        this.state = state
    }

    override fun clear() {
        state = EscalationState.NONE
    }
}
