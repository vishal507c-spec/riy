package com.vishal.riy.protection.events

import android.content.Context

/**
 * SharedPreferences-backed implementation of [ProtectionEventStore] — the
 * production counterpart of the unit-test [InMemoryProtectionEventStore].
 *
 * It is an append-only ring buffer persisted as one delimited string, using the
 * same persistence technology and the same one-file/one-key style as the
 * existing [com.vishal.riy.lock.PrefsLockStore]. No second logging system is
 * introduced: this class is only the storage for the existing
 * [ProtectionLogEvent] contract.
 *
 * The buffer is capped at [maxHistory] records, so a permanently running
 * protection service can never grow the file without bound.
 */
class PrefsProtectionEventStore(

    context: Context,

    private val maxHistory: Int = DEFAULT_HISTORY,

) : ProtectionEventStore {

    private val prefs = context.applicationContext
        .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    override fun append(event: ProtectionLogEvent) {
        val records = loadRecords().toMutableList()
        records += ProtectionLogEventSerialization.encode(event)
        // Keep the most recent [maxHistory] events, dropping the oldest.
        val capped = if (records.size > maxHistory) {
            records.subList(records.size - maxHistory, records.size).toList()
        } else {
            records
        }
        prefs.edit().putString(KEY_EVENTS, capped.joinToString(RECORD_SEP)).apply()
        // Event log changed → coalesced Drive snapshot (never throws).
        com.vishal.riy.drive.DriveSync.requestBackup()
    }

    override fun recent(limit: Int): List<ProtectionLogEvent> {
        val records = loadRecords()
        val from = (records.size - limit).coerceAtLeast(0)
        return records.subList(from, records.size)
            .mapNotNull { ProtectionLogEventSerialization.decode(it) }
    }

    override fun clear() {
        prefs.edit().remove(KEY_EVENTS).apply()
        // Event log changed → coalesced Drive snapshot (never throws).
        com.vishal.riy.drive.DriveSync.requestBackup()
    }

    private fun loadRecords(): List<String> {
        val raw = prefs.getString(KEY_EVENTS, null) ?: return emptyList()
        if (raw.isBlank()) return emptyList()
        return raw.split(RECORD_SEP).filter { it.isNotBlank() }
    }

    private companion object {
        const val PREFS_NAME = "riy_protection_events_prefs"
        const val KEY_EVENTS = "protection_events_v1"
        const val RECORD_SEP = "\n"

        /** Mirrors the cap the in-memory test store keeps. */
        const val DEFAULT_HISTORY = 200
    }
}

/**
 * Delimited (de)serialisation of one [ProtectionLogEvent], in the same style as
 * the existing [com.vishal.riy.lock.LockStateSerialization]. Pure Kotlin, so a
 * persisted log round-trips on the JVM without a device.
 *
 *     eventId | type | timestamp | message
 */
object ProtectionLogEventSerialization {

    private const val SEP = "|"
    private const val FIELD_COUNT = 4

    fun encode(event: ProtectionLogEvent): String = buildString {
        append(sanitize(event.eventId)).append(SEP)
        append(event.type.name).append(SEP)
        append(event.timestamp).append(SEP)
        append(sanitize(event.message))
    }

    fun decode(raw: String): ProtectionLogEvent? {
        if (raw.isBlank()) return null
        val parts = raw.split(SEP, limit = FIELD_COUNT)
        if (parts.size != FIELD_COUNT) return null
        return try {
            ProtectionLogEvent(
                eventId = parts[FIELD_EVENT_ID],
                type = ProtectionLogEvent.Type.valueOf(parts[FIELD_TYPE]),
                timestamp = parts[FIELD_TIMESTAMP].toLong(),
                message = parts[FIELD_MESSAGE],
            )
        } catch (_: IllegalArgumentException) {
            null
        } catch (_: NumberFormatException) {
            null
        }
    }

    /**
     * Removes the record separator and the field separator. A message may
     * otherwise legitimately contain a newline; without this the record after
     * it would be silently merged into the message field.
     */
    private fun sanitize(value: String): String =
        value.replace("\n", "").replace("\r", "").replace(SEP, "")

    private const val FIELD_EVENT_ID = 0
    private const val FIELD_TYPE = 1
    private const val FIELD_TIMESTAMP = 2
    private const val FIELD_MESSAGE = 3
}
