package com.vishal.riy.tracker

/**
 * Codec for the tracker's persisted payload.
 *
 * Follows the project's existing store conventions (a single delimited string in
 * SharedPreferences, versioned by the `_v1` key suffix), so the existing
 * `RiySnapshot` envelope picks the records up with no new backup machinery.
 *
 * Format — one record per line, `\u0000`-separated fields:
 * `epochDay \u0000 STATUS \u0000 createdAt \u0000 updatedAt`
 *
 * Records are always written SORTED by epochDay. That is not cosmetic: the
 * backup identity is the SHA-256 of the serialized snapshot, so an unstable
 * field order would change the hash on every save and manufacture a spurious
 * "newer generation" for no real change.
 */
object TrackerSerialization {

    private const val FIELD = '\u0000'
    private const val VERSION = 1

    fun encode(records: List<TrackerDay>): String = buildString {
        append("v").append(VERSION).append('\n')
        records.sortedBy { it.epochDay }.forEach { record ->
            append(record.epochDay).append(FIELD)
            append(record.status.name).append(FIELD)
            append(record.createdAt).append(FIELD)
            append(record.updatedAt).append('\n')
        }
    }

    /**
     * Decodes the payload. Malformed lines are SKIPPED, never thrown on and
     * never allowed to abort the whole restore: a single corrupt row must never
     * cost the user the rest of their history, and must never erase it either.
     */
    fun decode(raw: String?): List<TrackerDay> {
        if (raw.isNullOrBlank()) return emptyList()
        val out = ArrayList<TrackerDay>()
        raw.lineSequence().forEach { line ->
            val trimmed = line.trim()
            if (trimmed.isEmpty() || trimmed.startsWith("v")) return@forEach
            val parts = trimmed.split(FIELD)
            if (parts.size < 2) return@forEach
            val epochDay = parts[0].toLongOrNull() ?: return@forEach
            val status = TrackerStatus.parse(parts[1]) ?: return@forEach
            val createdAt = parts.getOrNull(2)?.toLongOrNull() ?: 0L
            val updatedAt = parts.getOrNull(3)?.toLongOrNull() ?: createdAt
            out.add(TrackerDay(epochDay, status, createdAt, updatedAt))
        }
        // One effective record per date: last write wins.
        return out
            .groupBy { it.epochDay }
            .map { (_, sameDay) -> sameDay.maxByOrNull { it.updatedAt }!! }
            .sortedBy { it.epochDay }
    }
}