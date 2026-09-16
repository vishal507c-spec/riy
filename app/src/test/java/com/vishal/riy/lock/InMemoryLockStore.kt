package com.vishal.riy.lock

/**
 * In-memory [LockStore] for unit tests. A new controller reading the SAME
 * instance is exactly what a fresh process does with SharedPreferences after
 * an app restart, a force-stop or a device reboot — which is how those
 * guarantees are tested without a device.
 */
class InMemoryLockStore : LockStore {
    var state: LockState = LockState.EMPTY
        private set

    override fun loadState(): LockState = state

    override fun saveState(state: LockState) {
        this.state = state
    }
}
