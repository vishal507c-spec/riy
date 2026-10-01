package com.vishal.riy.lock

import android.content.Context

/**
 * Persistence for the 2-hour lock. The interface keeps the engine testable on
 * the JVM; the production implementation is a single SharedPreferences file.
 * Because a lock is a wall-clock deadline read straight back from here,
 * whatever survives an app restart, force-stop or device reboot in this store
 * is exactly what keeps the user locked.
 */
interface LockStore {

    /** Last persisted lock state (may be [LockState.EMPTY]). */
    fun loadState(): LockState

    fun saveState(state: LockState)
}

/** SharedPreferences-backed implementation; keeps everything on the device. */
class PrefsLockStore(context: Context) : LockStore {

    private val prefs = context.applicationContext
        .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    override fun loadState(): LockState =
        LockStateSerialization.decode(prefs.getString(KEY_STATE, null)) ?: LockState.EMPTY

    override fun saveState(state: LockState) {
        prefs.edit().putString(KEY_STATE, LockStateSerialization.encode(state)).apply()
        // Protection state changed → coalesced Drive snapshot (never throws).
        com.vishal.riy.drive.DriveSync.requestBackup()
    }

    private companion object {
        const val PREFS_NAME = "riy_lock_prefs"
        const val KEY_STATE = "lock_state_v1"
    }
}
