package com.vishal.riy.awareness

/**
 * The optional, one-tap feeling tags a user may attach right after an adult
 * search was blocked. Selection is never forced (the UI always offers Skip)
 * and the data stays local. IDs are stable so they survive app updates.
 */
enum class Trigger(val id: String) {
    BOREDOM("boredom"),
    STRESS("stress"),
    LONELINESS("loneliness"),
    HABIT("habit"),
    LATE_NIGHT("late_night"),
    OTHER("other");

    companion object {
        fun fromId(id: String?): Trigger? = values().firstOrNull { it.id == id }
    }
}

/**
 * Bounded, local-only trigger history used for the gentle pattern insight
 * ("most common trigger over the last 7 days"). Only trigger id + calendar
 * day are ever stored — never the query, URL or any page content.
 */
object TriggerStats {

    const val MAX_RECORDS = 120

    data class Record(val triggerId: String, val dayKey: Int)

    /** Appends one record, keeping only the most recent [MAX_RECORDS]. */
    fun append(records: List<Record>, record: Record): List<Record> =
        (records + record).takeLast(MAX_RECORDS)

    /**
     * Most common trigger over the last 7 calendar days (today inclusive),
     * or null when there is no data in that window. Ties keep insertion order.
     */
    fun mostCommonInLast7Days(
        records: List<Record>,
        epochMillis: Long,
        timeZone: java.util.TimeZone,
    ): Trigger? {
        if (records.isEmpty()) return null
        val oldestDayKey = AwarenessLockEngine.dayKeyDaysBack(epochMillis, timeZone, 6)
        val counts = LinkedHashMap<String, Int>()
        for (record in records) {
            if (record.dayKey < oldestDayKey) continue
            counts[record.triggerId] = (counts[record.triggerId] ?: 0) + 1
        }
        if (counts.isEmpty()) return null
        val topId = counts.maxByOrNull { it.value }?.key ?: return null
        return Trigger.fromId(topId)
    }

    /** Encodes records as "id:dayKey;id:dayKey;..." for local persistence. */
    fun encode(records: List<Record>): String =
        records.joinToString(";") { "${it.triggerId}:${it.dayKey}" }

    fun decode(raw: String?): List<Record> {
        if (raw.isNullOrBlank()) return emptyList()
        return raw.split(';').mapNotNull { entry ->
            val parts = entry.split(':')
            if (parts.size != 2) return@mapNotNull null
            val id = parts[0]
            val day = parts[1].toIntOrNull() ?: return@mapNotNull null
            if (Trigger.fromId(id) == null) return@mapNotNull null
            Record(id, day)
        }
    }
}
