package com.vishal.riy.awareness

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Calendar
import java.util.TimeZone

/**
 * Engine-level tests for the progressive lock: the exact duration ladder, the
 * calendar-day counter reset, remaining-time accuracy and persistence
 * round-trip. All time is UTC so day boundaries are deterministic.
 */
class AwarenessLockEngineTest {

    private val utc: TimeZone = TimeZone.getTimeZone("UTC")

    @Test
    fun `duration ladder is exactly 2,4,8,16,24 hours and caps at 24`() {
        val expectedHours = intArrayOf(2, 4, 8, 16, 24, 24, 24, 24)
        for (i in expectedHours.indices) {
            val detection = i + 1
            val expected = expectedHours[i] * 3_600_000L
            assertEquals(
                "detection #$detection must lock for ${expectedHours[i]}h",
                expected,
                AwarenessLockEngine.durationForDetection(detection),
            )
        }
    }

    @Test
    fun `first detection of a day locks for exactly 2 hours`() {
        val now = utcMillis(2026, 9, 16, 12, 0)
        val next = AwarenessLockEngine.onAdultSearchDetected(LockState.EMPTY, now, utc)
        assertEquals(1, next.detectionCount)
        assertEquals(now + 2 * 3_600_000L, next.lockEndEpochMillis)
    }

    @Test
    fun `second through fifth detections escalate within the same day`() {
        val start = utcMillis(2026, 9, 16, 12, 0)
        var state = LockState.EMPTY
        val hours = longArrayOf(2, 4, 8, 16, 24)
        for (i in hours.indices) {
            // each detection happens one second later; its deadline is its own
            // timestamp plus the step's duration
            val now = start + i * 1_000L
            state = AwarenessLockEngine.onAdultSearchDetected(state, now, utc)
            assertEquals(now + hours[i] * 3_600_000L, state.lockEndEpochMillis)
            assertEquals(i + 1, state.detectionCount)
        }
    }

    @Test
    fun `new calendar day resets the counter and the duration to 2 hours`() {
        // 3rd detection late on Sep 16 -> 8h
        var state = LockState.EMPTY
        state = AwarenessLockEngine.onAdultSearchDetected(state, utcMillis(2026, 9, 16, 22, 0), utc)
        state = AwarenessLockEngine.onAdultSearchDetected(state, utcMillis(2026, 9, 16, 22, 30), utc)
        state = AwarenessLockEngine.onAdultSearchDetected(state, utcMillis(2026, 9, 16, 23, 0), utc)
        assertEquals(3, state.detectionCount)

        // Sep 17, 00:30 -> next calendar day -> counter resets, 1st detection = 2h
        val nextDay = utcMillis(2026, 9, 17, 0, 30)
        state = AwarenessLockEngine.onAdultSearchDetected(state, nextDay, utc)
        assertEquals(1, state.detectionCount)
        assertEquals(nextDay + 2 * 3_600_000L, state.lockEndEpochMillis)

        // and continues to escalate on the new day
        state = AwarenessLockEngine.onAdultSearchDetected(state, nextDay + 60_000L, utc)
        assertEquals(2, state.detectionCount)
        assertEquals(nextDay + 60_000L + 4 * 3_600_000L, state.lockEndEpochMillis)
    }

    @Test
    fun `isLocked and remainingMillis are accurate across the lock window`() {
        val now = utcMillis(2026, 9, 16, 12, 0)
        val state = AwarenessLockEngine.onAdultSearchDetected(LockState.EMPTY, now, utc)

        assertTrue(AwarenessLockEngine.isLocked(state, now))
        assertEquals(2 * 3_600_000L, AwarenessLockEngine.remainingMillis(state, now))
        assertEquals(90 * 60_000L, AwarenessLockEngine.remainingMillis(state, now + 30 * 60_000L))
        assertEquals(1_000L, AwarenessLockEngine.remainingMillis(state, now + 2 * 3_600_000L - 1_000L))
        assertEquals(0L, AwarenessLockEngine.remainingMillis(state, now + 2 * 3_600_000L))
        assertFalse(AwarenessLockEngine.isLocked(state, now + 2 * 3_600_000L))
    }

    @Test
    fun `clearIfExpired keeps a live lock but drops an expired one`() {
        val now = utcMillis(2026, 9, 16, 12, 0)
        var state = AwarenessLockEngine.onAdultSearchDetected(LockState.EMPTY, now, utc)

        // live lock must survive untouched (restart/reboot survival)
        state = AwarenessLockEngine.clearIfExpired(state, now + 60_000L)
        assertTrue(AwarenessLockEngine.isLocked(state, now + 60_000L))

        // once time runs out, it is cleared
        state = AwarenessLockEngine.clearIfExpired(state, now + 3 * 3_600_000L)
        assertFalse(AwarenessLockEngine.isLocked(state, now + 3 * 3_600_000L))
        assertEquals(AwarenessLockEngine.NO_LOCK, state.lockEndEpochMillis)
    }

    @Test
    fun `dayKey follows the timezone so daily reset uses local days`() {
        // 22:00 UTC on Sep 16 is already Sep 17 in a zone 5h30 ahead
        val instant = utcMillis(2026, 9, 16, 22, 0)
        assertEquals(20260916, AwarenessLockEngine.dayKey(instant, utc))
        assertEquals(
            20260917,
            AwarenessLockEngine.dayKey(instant, TimeZone.getTimeZone("GMT+5:30")),
        )
        assertNotEquals(
            AwarenessLockEngine.dayKey(instant, utc),
            AwarenessLockEngine.dayKey(instant, TimeZone.getTimeZone("GMT+5:30")),
        )
    }

    @Test
    fun `dayKeyDaysBack covers the last 7 calendar days`() {
        val now = utcMillis(2026, 9, 16, 12, 0)
        assertEquals(20260916, AwarenessLockEngine.dayKeyDaysBack(now, utc, 0))
        assertEquals(20260910, AwarenessLockEngine.dayKeyDaysBack(now, utc, 6))
        assertEquals(20260909, AwarenessLockEngine.dayKeyDaysBack(now, utc, 7))
    }

    @Test
    fun `remaining time formats as hours minutes seconds`() {
        assertEquals("00:00:00", AwarenessLockEngine.formatRemaining(0L))
        assertEquals("00:00:00", AwarenessLockEngine.formatRemaining(-1L))
        assertEquals("00:00:05", AwarenessLockEngine.formatRemaining(5_000L))
        assertEquals("01:01:01", AwarenessLockEngine.formatRemaining(3_661_000L))
        assertEquals("01:42:18", AwarenessLockEngine.formatRemaining((1 * 3_600 + 42 * 60 + 18) * 1_000L))
        assertEquals("24:00:00", AwarenessLockEngine.formatRemaining(24 * 3_600_000L))
    }

    @Test
    fun `lock state round-trips through persistence without losing the deadline`() {
        val now = utcMillis(2026, 9, 16, 12, 0)
        val state = AwarenessLockEngine.onAdultSearchDetected(LockState.EMPTY, now, utc)
        val restored = LockStateSerialization.decode(LockStateSerialization.encode(state))

        assertEquals(state, restored)
        assertTrue(AwarenessLockEngine.isLocked(restored!!, now))
        // the persisted deadline is still live much later -> restart is not a bypass
        assertTrue(AwarenessLockEngine.isLocked(restored, now + 119 * 60_000L))
        assertFalse(AwarenessLockEngine.isLocked(restored, now + 121 * 60_000L))
    }

    @Test
    fun `garbage persisted state decodes to nothing rather than a fake lock`() {
        assertEquals(null, LockStateSerialization.decode(null))
        assertEquals(null, LockStateSerialization.decode(""))
        assertEquals(null, LockStateSerialization.decode("garbage"))
        assertEquals(null, LockStateSerialization.decode("1|2|not-a-number|3"))
        assertEquals(LockState.EMPTY, LockStateSerialization.decode(LockStateSerialization.encode(LockState.EMPTY)))
    }

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
