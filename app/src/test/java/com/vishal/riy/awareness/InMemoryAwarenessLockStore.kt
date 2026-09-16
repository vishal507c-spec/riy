package com.vishal.riy.awareness

/**
 * In-memory [AwarenessLockStore] for unit tests. A new controller reading the
 * SAME instance is exactly what a fresh process does with SharedPreferences
 * after an app restart or a device reboot, which is how those guarantees are
 * tested without a device.
 */
class InMemoryAwarenessLockStore : AwarenessLockStore {
    var state: LockState = LockState.EMPTY
        private set
    var triggerRecords: List<TriggerStats.Record> = emptyList()
        private set
    var metrics: AwarenessMetrics = AwarenessMetrics()
        private set

    override fun loadState(): LockState = state
    override fun saveState(state: LockState) { this.state = state }
    override fun loadTriggerRecords(): List<TriggerStats.Record> = triggerRecords
    override fun saveTriggerRecords(records: List<TriggerStats.Record>) { this.triggerRecords = records }
    override fun loadMetrics(): AwarenessMetrics = metrics
    override fun saveMetrics(metrics: AwarenessMetrics) { this.metrics = metrics }
}
