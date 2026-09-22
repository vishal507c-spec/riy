package com.vishal.riy.protection.state

/**
 * In-memory [ProtectionStateStore] for unit tests. A new engine reading the
 * SAME instance is exactly what a fresh process does with persisted state after
 * an app restart, a force-stop or a device reboot — so those guarantees can be
 * tested without a device.
 *
 * [refuseSave] is a failure-injection switch used to prove that a write the
 * caller cannot read back is reported as a failure rather than as success.
 */
class InMemoryProtectionStateStore : ProtectionStateStore {

    @Volatile
    private var session: ProtectionSession? = null

    /** When set, [save] silently discards — modelling an unreadable store. */
    @Volatile
    var refuseSave: Boolean = false

    override fun load(): ProtectionSession? = session

    override fun save(session: ProtectionSession) {
        if (refuseSave) return
        this.session = session
    }

    override fun clear() {
        session = null
    }

    override fun current(): ProtectionSession? = session
}
