package com.vishal.riy.awareness

import android.content.Context

/**
 * Cumulative, local-only progress counters. "Adult searches avoided" counts
 * adult search results pages that were never loaded; "awareness pauses
 * completed" counts pauses the user sat through. Streaks are deliberately NOT
 * a metric here.
 */
data class AwarenessMetrics(
    val adultSearchesAvoided: Int = 0,
    val awarenessPausesCompleted: Int = 0,
)

/**
 * Local persistence for the progressive lock, trigger history and progress
 * counters. The interface keeps the engine testable; the production
 * implementation is a single SharedPreferences file. Because a lock is a
 * wall-clock deadline read straight back from here, whatever survives a
 * restart/reboot in this store is exactly what protects the user.
 */
interface AwarenessLockStore {

    /** Last persisted lock state (may be [LockState.EMPTY]). */
    fun loadState(): LockState

    fun saveState(state: LockState)

    fun loadTriggerRecords(): List<TriggerStats.Record>

    fun saveTriggerRecords(records: List<TriggerStats.Record>)

    fun loadMetrics(): AwarenessMetrics

    fun saveMetrics(metrics: AwarenessMetrics)
}

/** SharedPreferences-backed implementation; keeps everything on the device. */
class PrefsAwarenessLockStore(context: Context) : AwarenessLockStore {

    private val prefs = context.applicationContext
        .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    override fun loadState(): LockState =
        LockStateSerialization.decode(prefs.getString(KEY_STATE, null)) ?: LockState.EMPTY

    override fun saveState(state: LockState) {
        prefs.edit().putString(KEY_STATE, LockStateSerialization.encode(state)).apply()
    }

    override fun loadTriggerRecords(): List<TriggerStats.Record> =
        TriggerStats.decode(prefs.getString(KEY_TRIGGERS, null))

    override fun saveTriggerRecords(records: List<TriggerStats.Record>) {
        prefs.edit().putString(KEY_TRIGGERS, TriggerStats.encode(records)).apply()
    }

    override fun loadMetrics(): AwarenessMetrics = AwarenessMetrics(
        adultSearchesAvoided = prefs.getInt(KEY_AVOIDED, 0),
        awarenessPausesCompleted = prefs.getInt(KEY_PAUSES, 0),
    )

    override fun saveMetrics(metrics: AwarenessMetrics) {
        prefs.edit()
            .putInt(KEY_AVOIDED, metrics.adultSearchesAvoided)
            .putInt(KEY_PAUSES, metrics.awarenessPausesCompleted)
            .apply()
    }

    private companion object {
        const val PREFS_NAME = "awareness_lock_prefs"
        const val KEY_STATE = "lock_state_v1"
        const val KEY_TRIGGERS = "trigger_records_v1"
        const val KEY_AVOIDED = "adult_searches_avoided"
        const val KEY_PAUSES = "awareness_pauses_completed"
    }
}
