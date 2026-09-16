package com.vishal.riy.awareness

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.util.Calendar
import java.util.TimeZone

/**
 * Trigger history stays local and bounded, and the 7-day insight behaves at
 * the window boundary. Privacy note: only trigger id + calendar day are kept.
 */
class TriggerStatsTest {

    private val utc: TimeZone = TimeZone.getTimeZone("UTC")

    @Test
    fun `empty history reports no common trigger`() {
        assertNull(TriggerStats.mostCommonInLast7Days(emptyList(), now(), utc))
    }

    @Test
    fun `single record reports that trigger`() {
        val records = listOf(TriggerStats.Record(Trigger.STRESS.id, dayKeyNow()))
        assertEquals(Trigger.STRESS, TriggerStats.mostCommonInLast7Days(records, now(), utc))
    }

    @Test
    fun `highest count wins and ties keep insertion order`() {
        val today = dayKeyNow()
        val records = listOf(
            TriggerStats.Record(Trigger.LATE_NIGHT.id, today),
            TriggerStats.Record(Trigger.STRESS.id, today),
            TriggerStats.Record(Trigger.LATE_NIGHT.id, today),
        )
        assertEquals(Trigger.LATE_NIGHT, TriggerStats.mostCommonInLast7Days(records, now(), utc))
    }

    @Test
    fun `records inside the last 7 calendar days count, older ones do not`() {
        val n = now()
        // 6 days back is the oldest included day; 7 days back is excluded
        val inside = TriggerStats.Record(Trigger.HABIT.id, AwarenessLockEngine.dayKeyDaysBack(n, utc, 6))
        val boundary = TriggerStats.Record(Trigger.BOREDOM.id, AwarenessLockEngine.dayKeyDaysBack(n, utc, 7))
        val records = listOf(inside, boundary)

        assertEquals(Trigger.HABIT, TriggerStats.mostCommonInLast7Days(records, n, utc))

        // when only out-of-window data exists, nothing is reported
        assertEquals(null, TriggerStats.mostCommonInLast7Days(listOf(boundary), n, utc))
    }

    @Test
    fun `history is bounded to the most recent records`() {
        var records = emptyList<TriggerStats.Record>()
        for (i in 0 until 200) {
            records = TriggerStats.append(records, TriggerStats.Record(Trigger.OTHER.id, 20260901 + i / 10))
        }
        assertEquals(TriggerStats.MAX_RECORDS, records.size)
    }

    @Test
    fun `records round-trip through persistence`() {
        val records = listOf(
            TriggerStats.Record(Trigger.LATE_NIGHT.id, 20260916),
            TriggerStats.Record(Trigger.STRESS.id, 20260917),
        )
        val restored = TriggerStats.decode(TriggerStats.encode(records))
        assertEquals(records, restored)
    }

    @Test
    fun `unknown or corrupt ids are dropped, never crash`() {
        assertEquals(emptyList<TriggerStats.Record>(), TriggerStats.decode(null))
        assertEquals(emptyList<TriggerStats.Record>(), TriggerStats.decode(""))
        assertEquals(emptyList<TriggerStats.Record>(), TriggerStats.decode("notARealId:20260916"))
        assertEquals(
            listOf(TriggerStats.Record(Trigger.STRESS.id, 20260916)),
            TriggerStats.decode("notARealId:20260916;stress:20260916;bad"),
        )
    }

    private fun now(): Long = utcMillis(2026, 9, 16, 12, 0)
    private fun dayKeyNow(): Int = AwarenessLockEngine.dayKey(now(), utc)

    private fun utcMillis(year: Int, month: Int, day: Int, hour: Int, minute: Int): Long {
        val calendar = Calendar.getInstance(utc)
        calendar.set(Calendar.YEAR, year)
        calendar.set(Calendar.MONTH, month - 1)
        calendar.set(Calendar.DAY_OF_MONTH, day)
        calendar.set(Calendar.HOUR_OF_DAY, hour)
        calendar.set(Calendar.MINUTE, minute)
        calendar.set(Calendar.SECOND, 0)
        calendar.set(Calendar.MILLISECOND, 0)
        return calendar.timeInMillis
    }
}
