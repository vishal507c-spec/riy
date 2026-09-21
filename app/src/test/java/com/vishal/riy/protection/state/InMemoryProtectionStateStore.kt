package com.vishal.riy.protection.state

/**
 * In-memory [ProtectionStateStore] for unit tests. A new engine reading the
 * SAME instance is exactly what a fresh process does with persisted state after
 * an app restart, a force-stop or a device reboot — so those guarantees can be
 * tested without a device.
 */
class InMemoryProtectionStateStore : ProtectionStateStore {

    @Volatile
    private var session: ProtectionSession? = null

    override fun load(): ProtectionSession? = session

    override fun save(session: ProtectionSession) {
        this.session = session
    }

    override fun clear() {
        session = null
    }

    override fun current(): ProtectionSession? = session
}
