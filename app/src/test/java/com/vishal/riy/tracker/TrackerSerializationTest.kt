package com.vishal.riy.tracker

import com.vishal.riy.drive.RiySnapshot
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.TimeZone

/**
 * Serialization, integration with the EXISTING backup, and compatibility with
 * backups written before the tracker existed.
 *
 * Fixtures build records through [record] so the field separator is written as a
 * Kotlin escape rather than a literal control character — a stray byte in a
 * hand-written fixture would otherwise make these tests pass for the wrong
 * reason.
 */
class TrackerSerializationTest {

    private val sep = "\u0000"

    private fun record(
        day: Long,
        status: String,
        created: Long = 1L,
        updated: Long = 1L,
    ) = "$day$sep$status$sep$created$sep$updated"

    private fun payload(vararg lines: String) = "v1\n" + lines.joinToString("\n") + "\n"

    // ------------------------------------------------------------ round trip

    @Test
    fun `round trips every status`() {
        val records = listOf(
            TrackerDay(100L, TrackerStatus.YES, 1_000L, 2_000L),
            TrackerDay(101L, TrackerStatus.NO, 1_000L, 2_000L),
            TrackerDay(102L, TrackerStatus.UNKNOWN, 1_000L, 2_000L),
        )
        assertEquals(records, TrackerSerialization.decode(TrackerSerialization.encode(records)))
    }

    @Test
    fun `empty and null payloads decode to nothing`() {
        assertTrue(TrackerSerialization.decode(null).isEmpty())
        assertTrue(TrackerSerialization.decode("").isEmpty())
        assertTrue(TrackerSerialization.decode("v1\n").isEmpty())
    }

    @Test
    fun `a corrupt line never costs the rest of the history`() {
        val decoded = TrackerSerialization.decode(
            payload(
                record(999L, "YES").replace("999", "not-a-number"), // unparseable date
                "garbage-without-separators",
                record(101L, "NO"),
                record(102L, "NONSENSE"),
            ),
        )
        // The unparseable date and the separator-less line are skipped; the valid
        // row survives, and the unrecognised STATUS is preserved as UNKNOWN
        // rather than deleting a day the user actually answered.
        assertEquals(2, decoded.size)
        assertEquals(101L, decoded.first { it.epochDay == 101L }.epochDay)
        assertEquals(TrackerStatus.UNKNOWN, decoded.first { it.epochDay == 102L }.status)
    }

    @Test
    fun `an unknown status degrades to UNKNOWN rather than being dropped`() {
        val decoded = TrackerSerialization.decode(payload(record(100L, "MYSTERY")))
        assertEquals(1, decoded.size)
        assertEquals(TrackerStatus.UNKNOWN, decoded[0].status)
    }

    @Test
    fun `encoding is stable regardless of input order`() {
        // The backup identity is a SHA of the serialized bytes, so an unstable
        // order would fabricate a new generation on every save.
        val a = listOf(TrackerDay(2L, TrackerStatus.NO), TrackerDay(1L, TrackerStatus.YES))
        val b = listOf(TrackerDay(1L, TrackerStatus.YES), TrackerDay(2L, TrackerStatus.NO))
        assertEquals(TrackerSerialization.encode(a), TrackerSerialization.encode(b))
    }

    @Test
    fun `duplicate dates collapse to the most recent edit`() {
        val decoded = TrackerSerialization.decode(
            payload(
                record(100L, "YES", updated = 100L),
                record(100L, "NO", updated = 200L),
            ),
        )
        assertEquals(1, decoded.size)
        assertEquals(TrackerStatus.NO, decoded[0].status)
    }

    @Test
    fun `not sure is accepted as a legacy alias`() {
        assertEquals(TrackerStatus.UNKNOWN, TrackerStatus.parse("NOT_SURE"))
        assertEquals(TrackerStatus.UNKNOWN, TrackerStatus.parse("not sure"))
        assertEquals(TrackerStatus.UNKNOWN, TrackerStatus.parse("Unknown"))
        assertEquals(TrackerStatus.YES, TrackerStatus.parse("yes"))
        assertEquals(TrackerStatus.NO, TrackerStatus.parse("no"))
        assertEquals(null, TrackerStatus.parse(null))
        assertEquals(null, TrackerStatus.parse("  "))
    }

    // ---------------------------------------------- existing backup integration

    @Test
    fun `tracker store is part of the EXISTING snapshot payload`() {
        // No second backup system: the records ride along in the same document
        // as every other RIY store.
        assertTrue(RiySnapshot.STORE_FILES.contains(TrackerStore.PREFS_NAME))
    }

    @Test
    fun `store file list stays sorted so snapshot bytes stay canonical`() {
        assertEquals(RiySnapshot.STORE_FILES.sorted(), RiySnapshot.STORE_FILES)
    }

    @Test
    fun `tracker records survive a real snapshot round trip`() {
        val records = listOf(
            TrackerDay(100L, TrackerStatus.YES, 1L, 2L),
            TrackerDay(101L, TrackerStatus.NO, 1L, 2L),
        )
        val bytes = RiySnapshot.build(
            stores = mapOf(
                TrackerStore.PREFS_NAME to mapOf(
                    TrackerStore.KEY_DAYS to TrackerSerialization.encode(records),
                ),
            ),
            snapshotId = "snap-1",
            generation = 1,
            createdAt = 123L,
            appVersion = "1.0.0",
        )
        val decoded = RiySnapshot.parse(bytes)
        assertEquals(1L, decoded?.generation)
        val restored = decoded!!.stores[TrackerStore.PREFS_NAME]
        val raw = restored?.get(TrackerStore.KEY_DAYS)?.toValue()
        assertTrue("tracker key must be present in the backup", raw is String)
        val days = TrackerSerialization.decode(raw as String)
        assertEquals(2, days.size)
        assertEquals(TrackerStatus.NO, days.first { it.epochDay == 101L }.status)
    }

    @Test
    fun `totals recalculate identically after a restore`() {
        // The acceptance criterion: counters and streaks must be the same after
        // a save -> backup -> restore cycle as they were before it.
        val original = listOf(
            TrackerDay(100L, TrackerStatus.NO, 1L, 2L),
            TrackerDay(101L, TrackerStatus.NO, 1L, 2L),
            TrackerDay(102L, TrackerStatus.YES, 1L, 2L),
        )
        val encoded = TrackerSerialization.encode(original)
        val restored = TrackerSerialization.decode(encoded)
        val before = TrackerCalculator.compute(original, todayEpochDay = 103L)
        val after = TrackerCalculator.compute(restored, todayEpochDay = 103L)
        assertEquals(before, after)
        assertEquals(2, after.noDays)
        assertEquals(1, after.yesDays)
        // 100 and 101 are consecutive NOs; 102 is YES, so the CURRENT streak is
        // broken at the latest completed day while the historical best stays 2.
        assertEquals(0, after.currentStreak)
        assertEquals(2, after.longestStreak)
    }

    @Test
    fun `a backup written before the tracker existed still restores`() {
        // Old VAULT: no tracker store at all. It must parse fine and simply
        // contribute no tracker data.
        val bytes = RiySnapshot.build(
            stores = mapOf("riy_lock_prefs" to mapOf("lock_state_v1" to "x")),
            snapshotId = "old-snap",
            generation = 7,
            createdAt = 1L,
            appVersion = "2.6.0",
        )
        val decoded = RiySnapshot.parse(bytes)
        assertTrue("old backup must remain readable", decoded != null)
        assertEquals(7L, decoded?.generation)
        assertFalse(decoded!!.stores.containsKey(TrackerStore.PREFS_NAME))
    }

    @Test
    fun `a tracker payload is readable by a build that has no tracker`() {
        // Forward compatibility: an older RIY that knows nothing about the
        // tracker must still parse the document (it ignores the unknown store)
        // instead of rejecting the whole backup.
        val bytes = RiySnapshot.build(
            stores = mapOf(
                "riy_lock_prefs" to mapOf("lock_state_v1" to "x"),
                TrackerStore.PREFS_NAME to mapOf(
                    TrackerStore.KEY_DAYS to TrackerSerialization.encode(
                        listOf(TrackerDay(100L, TrackerStatus.NO)),
                    ),
                ),
            ),
            snapshotId = "new-snap",
            generation = 9,
            createdAt = 1L,
            appVersion = "1.0.0",
        )
        val decoded = RiySnapshot.parse(bytes)
        assertTrue(decoded != null)
        assertEquals(9L, decoded?.generation)
    }

    @Test
    fun `format version is unchanged so existing backups stay compatible`() {
        // Bumping this would make every previously uploaded VAULT unreadable.
        assertEquals(1, RiySnapshot.FORMAT_VERSION)
    }

    @Test
    fun `snapshot parsing rejects malformed payloads without throwing`() {
        assertEquals(null, RiySnapshot.parse("not json".toByteArray()))
        assertEquals(null, RiySnapshot.parse(ByteArray(0)))
        assertEquals(
            null,
            RiySnapshot.parse(JSONObject().put("formatVersion", 99).toString().toByteArray()),
        )
    }
}

/** The pending-check-in decision, including the once-a-day guard. */
class TrackerCheckInTest {

    private val today = 1_000L

    @Test
    fun `yesterday is pending when it has no record`() {
        assertEquals(999L, TrackerCheckIn.pendingDate(today) { false })
    }

    @Test
    fun `nothing is pending once yesterday is answered`() {
        val answered = setOf(999L)
        assertTrue(TrackerCheckIn.pendingDate(today) { it in answered } == null)
    }

    @Test
    fun `today is never the question`() {
        // Today is still in progress; asking about it would ask the user to
        // predict their own future.
        val records = setOf(999L, 1_000L)
        assertTrue(TrackerCheckIn.pendingDate(today) { it in records } == null)
        assertEquals(999L, TrackerCheckIn.pendingDate(today) { false })
    }

    @Test
    fun `older missing days do not trigger a backlog of popups`() {
        // -1 answered, -2..-9 missing. The prompt stays quiet; the older gaps
        // remain visible as "not recorded" and can be filled by hand.
        val records = setOf(999L)
        assertTrue(TrackerCheckIn.pendingDate(today) { it in records } == null)
    }

    @Test
    fun `app closed for several days still asks exactly one question`() {
        assertEquals(999L, TrackerCheckIn.pendingDate(today) { false })
    }

    @Test
    fun `auto prompt is shown at most once per day`() {
        assertTrue(TrackerCheckIn.shouldAutoPrompt(999L, alreadyPromptedToday = false))
        assertFalse(TrackerCheckIn.shouldAutoPrompt(999L, alreadyPromptedToday = true))
        assertFalse(TrackerCheckIn.shouldAutoPrompt(null, alreadyPromptedToday = false))
    }

    @Test
    fun `the first ever day has nothing to ask about`() {
        // Epoch day 0: "yesterday" would be negative, which is not a real date.
        assertTrue(TrackerCheckIn.pendingDate(0L) { false } == null)
    }
}

/** Reminder scheduling maths — the part that must survive DST and timezones. */
class TrackerReminderTest {

    private val tz = TimeZone.getTimeZone("Asia/Kolkata")

    @Test
    fun `next occurrence is later today when the time has not passed`() {
        val now = TrackerDates.startOfDayMillis(20_000L, tz) + 6 * 3_600_000L // 06:00
        val next = TrackerReminder.nextOccurrenceMillis(9, 0, now, tz)
        assertTrue(next > now)
        assertEquals(20_000L, TrackerDates.epochDayOf(next, tz))
    }

    @Test
    fun `next occurrence rolls to tomorrow when the time has passed`() {
        val now = TrackerDates.startOfDayMillis(20_000L, tz) + 10 * 3_600_000L // 10:00
        val next = TrackerReminder.nextOccurrenceMillis(9, 0, now, tz)
        assertEquals(20_001L, TrackerDates.epochDayOf(next, tz))
    }

    @Test
    fun `next occurrence lands exactly on the requested minute`() {
        val now = TrackerDates.startOfDayMillis(20_000L, tz) + 3 * 3_600_000L
        val next = TrackerReminder.nextOccurrenceMillis(7, 45, now, tz)
        val c = java.util.Calendar.getInstance(tz).apply { timeInMillis = next }
        assertEquals(7, c.get(java.util.Calendar.HOUR_OF_DAY))
        assertEquals(45, c.get(java.util.Calendar.MINUTE))
        assertEquals(0, c.get(java.util.Calendar.SECOND))
    }

    @Test
    fun `schedule works across a DST transition`() {
        // US "spring forward" is 2024-03-10; the alarm must still fire on a real
        // day rather than on a non-existent local time.
        val newYork = TimeZone.getTimeZone("America/New_York")
        val now = TrackerDates.startOfDayMillis(
            TrackerDates.daysFromCivil(2024, 3, 9),
            newYork,
        ) + 12 * 3_600_000L
        val next = TrackerReminder.nextOccurrenceMillis(2, 30, now, newYork)
        assertTrue("alarm must be in the future", next > now)
        assertTrue(
            TrackerDates.epochDayOf(next, newYork) >= TrackerDates.daysFromCivil(2024, 3, 10),
        )
    }

    @Test
    fun `reminder stays a daily occurrence`() {
        val now = TrackerDates.startOfDayMillis(20_000L, tz) + 10 * 3_600_000L
        val first = TrackerReminder.nextOccurrenceMillis(9, 0, now, tz)
        assertEquals(20_001L, TrackerDates.epochDayOf(first, tz))
        // Re-arming from the exact fire moment must move to the following day,
        // otherwise the alarm would fire repeatedly at 09:00.
        val second = TrackerReminder.nextOccurrenceMillis(9, 0, first, tz)
        assertEquals(20_002L, TrackerDates.epochDayOf(second, tz))
    }
}