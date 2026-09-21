package com.vishal.riy.protection.events

/**
 * Append-only storage for [ProtectionLogEvent]s.
 *
 * Platform-independent contract: it knows nothing about Android, so the same
 * interface backs a persistent implementation and an
 * [InMemoryProtectionEventStore] for unit tests.
 *
 * Phase 2 establishes the contract only.
 */
interface ProtectionEventStore {

    /** Records [event]. Never throws; a store that cannot write logs instead. */
    fun append(event: ProtectionLogEvent)

    /**
     * The most recent [limit] events in chronological order (oldest first).
     * Implementations may cap the total retained history.
     */
    fun recent(limit: Int = DEFAULT_LIMIT): List<ProtectionLogEvent>

    /** Removes all recorded events. Intended for diagnostics/tests. */
    fun clear()

    companion object {

        /** How many recent events the UI log shows. */
        const val DEFAULT_LIMIT = 20
    }
}
