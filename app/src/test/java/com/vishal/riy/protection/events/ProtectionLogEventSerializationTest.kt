package com.vishal.riy.protection.events

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Round-trips the protection log encoding WITHOUT a device, so the persisted
 * event history can be trusted across an app restart or a reboot.
 */
class ProtectionLogEventSerializationTest {

    @Test
    fun `an event round-trips exactly`() {
        val event = ProtectionLogEvent(
            eventId = "event-1",
            type = ProtectionLogEvent.Type.CONTENT_DETECTED,
            timestamp = NOW,
            message = "ADULT_DOMAIN_DNS_LOOKUP via DNS_FILTER: risk=CONFIRMED",
        )

        assertEquals(event, decode(encode(event)))
    }

    @Test
    fun `every recorded type round-trips`() {
        ProtectionLogEvent.Type.values().forEach { type ->
            val event = ProtectionLogEvent(
                eventId = "event-$type",
                type = type,
                timestamp = NOW,
                message = "detail",
            )

            assertEquals(
                "type $type must survive persistence",
                event,
                decode(encode(event)),
            )
        }
    }

    @Test
    fun `a message containing the field separator cannot corrupt the record`() {
        val event = ProtectionLogEvent(
            eventId = "event-2",
            type = ProtectionLogEvent.Type.INTEGRITY_MISMATCH,
            timestamp = NOW,
            message = "restricted session NOT applied: |FAILED|",
        )

        val restored = decode(encode(event))

        assertEquals(event.eventId, restored?.eventId)
        assertEquals(event.type, restored?.type)
        assertEquals(event.timestamp, restored?.timestamp)
        assertEquals(event.message.replace("|", ""), restored?.message)
    }

    @Test
    fun `a message containing newlines stays one record`() {
        val event = ProtectionLogEvent(
            eventId = "event-3",
            type = ProtectionLogEvent.Type.RESTRICTION_ENTERED,
            timestamp = NOW,
            message = "line one\nline two\r\nline three",
        )

        // A newline is the RECORD separator, so it must be stripped on write —
        // otherwise one event would reappear as three on the next read.
        val restored = decode(encode(event))

        assertEquals(1, encode(event).split("\n").size)
        assertEquals(event.message.replace("\n", "").replace("\r", ""), restored?.message)
    }

    @Test
    fun `blank and malformed input decodes to nothing`() {
        assertNull(decode(""))
        assertNull(decode("   "))
        assertNull(decode("event-1|CONTENT_DETECTED"))
        assertNull(decode("event-1|CONTENT_DETECTED|not-a-millis|detail"))
        assertNull(decode("event-1|NOT_A_REAL_TYPE|$NOW|detail"))
    }

    private companion object {
        const val NOW = 1_000_000L

        private fun encode(event: ProtectionLogEvent): String =
            ProtectionLogEventSerialization.encode(event)

        private fun decode(raw: String): ProtectionLogEvent? =
            ProtectionLogEventSerialization.decode(raw)
    }
}
