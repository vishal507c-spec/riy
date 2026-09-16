package com.vishal.riy.lock

/**
 * Immutable view the Compose UI renders; produced from the live clock so the
 * countdown stays accurate.
 */
data class LockSnapshot(
    val locked: Boolean,
    val remainingMillis: Long,
)

/**
 * The lock state machine. Pure Kotlin: it owns no Android object, takes an
 * injectable clock, and talks only to [LockStore], so lock survival across an
 * app restart, a force-stop and a device reboot is unit-testable on the JVM.
 *
 * The VPN service is what arms a lock (it is the only component that can
 * actually observe an adult-content request at the DNS layer). This class is
 * what the UI reads: on every [tick] it re-reads the persisted deadline, so a
 * detection recorded by the service while the UI was in the background is
 * reflected immediately, and a deadline that has run out clears the lock.
 */
class LockController(
    private val store: LockStore,
    private val clock: () -> Long = { System.currentTimeMillis() },
) {

    private var state: LockState = LockEngine.clearIfExpired(store.loadState(), clock())

    /** True while a lock is live right now (read from the persisted deadline). */
    fun isLocked(): Boolean = LockEngine.isLocked(state, clock())

    /** Milliseconds left in the current lock. */
    fun remainingMillis(): Long = LockEngine.remainingMillis(state, clock())

    /**
     * Called ~4x/second by the ViewModel. Re-reads the store (the service may
     * have armed a new lock since the last tick) and clears a lock whose time
     * has run out. Persistence is written only on a real transition.
     */
    fun tick() {
        val now = clock()
        val persisted = store.loadState()
        val effective = LockEngine.clearIfExpired(persisted, now)
        if (effective !== persisted) store.saveState(effective)
        state = effective
    }

    /** Re-reads everything from the store — exactly what a fresh process does. */
    fun reloadFromStore() {
        state = LockEngine.clearIfExpired(store.loadState(), clock())
    }

    fun snapshot(): LockSnapshot = LockSnapshot(
        locked = isLocked(),
        remainingMillis = remainingMillis(),
    )
}
