package com.vishal.riy.protection.events

/**
 * In-memory [ProtectionEventStore] for unit tests. Keeps the most recent
 * [MAX_HISTORY] events and returns them oldest-first, exactly as a persistent
 * implementation would.
 */
class InMemoryProtectionEventStore : ProtectionEventStore {

    private val events: MutableList<ProtectionLogEvent> = ArrayList()

    override fun append(event: ProtectionLogEvent) {
        synchronized(events) {
            events.add(event)
            if (events.size > MAX_HISTORY) events.removeAt(0)
        }
    }

    override fun recent(limit: Int): List<ProtectionLogEvent> = synchronized(events) {
        val from = (events.size - limit).coerceAtLeast(0)
        events.subList(from, events.size).toList()
    }

    override fun clear() {
        synchronized(events) { events.clear() }
    }

    private companion object {
        const val MAX_HISTORY = 200
    }
}
