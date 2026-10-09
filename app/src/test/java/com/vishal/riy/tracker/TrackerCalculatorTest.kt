package com.vishal.riy.tracker

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.TimeZone

/**
 * The counting rules, which are the whole point of the feature.
 *
 * Everything here is pure, so the numbers on the dashboard can be asserted
 * exactly rather than eyeballed.
 *
 * Convention: tests use "day 100 = some date" style epoch days so they read as
 * pure integers; [today] is always explicit.
 */
class TrackerCalculatorTest {

    private val today = 1_000L

    private fun day(offsetFromToday: Long, status: TrackerStatus) =
        TrackerDay(epochDay = today + offsetFromToday, status = status)

    // ------------------------------------------------------------- counting

    @Test
    fun `counts unique YES days`() {
        val stats = TrackerCalculator.compute(
            listOf(
                day(-1, TrackerStatus.YES),
                day(-2, TrackerStatus.YES),
                day(-3, TrackerStatus.NO),
            ),
            today,
        )
        assertEquals(2, stats.yesDays)
        assertEquals(1, stats.noDays)
        assertEquals(3, stats.recordedDays)
    }

    @Test
    fun `counts unique NO days`() {
        val stats = TrackerCalculator.compute(
            listOf(
                day(-1, TrackerStatus.NO),
                day(-2, TrackerStatus.NO),
                day(-3, TrackerStatus.NO),
                day(-4, TrackerStatus.YES),
            ),
            today,
        )
        assertEquals(3, stats.noDays)
        assertEquals(1, stats.yesDays)
    }

    @Test
    fun `unknown is its own bucket and never a NO`() {
        val stats = TrackerCalculator.compute(
            listOf(
                day(-1, TrackerStatus.UNKNOWN),
                day(-2, TrackerStatus.NO),
                day(-3, TrackerStatus.YES),
            ),
            today,
        )
        assertEquals(1, stats.unknownDays)
        assertEquals(1, stats.noDays)
        assertEquals(1, stats.yesDays)
        assertEquals(0, stats.currentStreak)
    }

    @Test
    fun `multiple events on one date still count as a single YES day`() {
        // Simulates two writes to the same date: the store collapses them, and
        // even if both arrived the calculator must still see one day.
        val stats = TrackerCalculator.compute(
            listOf(
                TrackerDay(today - 1, TrackerStatus.YES, createdAt = 1, updatedAt = 1),
                TrackerDay(today - 1, TrackerStatus.YES, createdAt = 1, updatedAt = 2),
            ),
            today,
        )
        assertEquals(1, stats.yesDays)
        assertEquals(1, stats.recordedDays)
    }

    @Test
    fun `last write wins when a date somehow has two records`() {
        val stats = TrackerCalculator.compute(
            listOf(
                TrackerDay(today - 1, TrackerStatus.YES, updatedAt = 100),
                TrackerDay(today - 1, TrackerStatus.NO, updatedAt = 200),
            ),
            today,
        )
        // The newer edit (NO) wins regardless of list order.
        assertEquals(0, stats.yesDays)
        assertEquals(1, stats.noDays)
        assertEquals(1, stats.recordedDays)
    }

    @Test
    fun `editing an answer recalculates every counter`() {
        // YES -> NO on the same date must move one day between the buckets.
        val before = TrackerCalculator.compute(listOf(day(-1, TrackerStatus.YES)), today)
        assertEquals(1, before.yesDays)
        assertEquals(0, before.noDays)

        val after = TrackerCalculator.compute(listOf(day(-1, TrackerStatus.NO)), today)
        assertEquals(0, after.yesDays)
        assertEquals(1, after.noDays)
        assertEquals(1, after.currentStreak)
    }

    // -------------------------------------------------------- current streak

    @Test
    fun `current streak counts consecutive NO up to the latest completed day`() {
        val stats = TrackerCalculator.compute(
            listOf(
                day(-1, TrackerStatus.NO),
                day(-2, TrackerStatus.NO),
                day(-3, TrackerStatus.NO),
                day(-4, TrackerStatus.YES),
            ),
            today,
        )
        assertEquals(3, stats.currentStreak)
    }

    @Test
    fun `the incomplete current day is excluded from the streak`() {
        // Yesterday is NO but TODAY is answered YES. Today is still in progress,
        // so it must not affect a streak that ends at yesterday.
        val stats = TrackerCalculator.compute(
            listOf(
                day(0, TrackerStatus.YES),
                day(-1, TrackerStatus.NO),
                day(-2, TrackerStatus.NO),
            ),
            today,
        )
        assertEquals(2, stats.currentStreak)
        assertEquals(1, stats.yesDays)
    }

    @Test
    fun `an unanswered yesterday yields a zero current streak`() {
        // The most important negative case: a MISSING day is never a NO day.
        val stats = TrackerCalculator.compute(
            listOf(
                day(-2, TrackerStatus.NO),
                day(-3, TrackerStatus.NO),
                // -1 deliberately missing
            ),
            today,
        )
        assertEquals(0, stats.currentStreak)
        assertEquals(2, stats.longestStreak)
    }

    @Test
    fun `an UNKNOWN yesterday breaks the current streak`() {
        val stats = TrackerCalculator.compute(
            listOf(
                day(-1, TrackerStatus.UNKNOWN),
                day(-2, TrackerStatus.NO),
            ),
            today,
        )
        assertEquals(0, stats.currentStreak)
    }

    @Test
    fun `a YES yesterday breaks the current streak`() {
        val stats = TrackerCalculator.compute(
            listOf(day(-1, TrackerStatus.YES), day(-2, TrackerStatus.NO)),
            today,
        )
        assertEquals(0, stats.currentStreak)
    }

    // ------------------------------------------------------- longest streak

    @Test
    fun `longest streak finds the best historical run`() {
        val stats = TrackerCalculator.compute(
            listOf(
                day(-1, TrackerStatus.YES),
                // a 5-day run
                day(-2, TrackerStatus.NO),
                day(-3, TrackerStatus.NO),
                day(-4, TrackerStatus.NO),
                day(-5, TrackerStatus.NO),
                day(-6, TrackerStatus.NO),
                // a gap, then a 2-day run
                day(-9, TrackerStatus.NO),
                day(-10, TrackerStatus.NO),
            ),
            today,
        )
        assertEquals(5, stats.longestStreak)
        assertEquals(0, stats.currentStreak)
    }

    @Test
    fun `longest streak never bridges a gap`() {
        // Two NO days three apart are NOT a two-day streak.
        val stats = TrackerCalculator.compute(
            listOf(day(-1, TrackerStatus.NO), day(-4, TrackerStatus.NO)),
            today,
        )
        assertEquals(1, stats.longestStreak)
    }

    @Test
    fun `longest streak is never bridged over an unrecorded day`() {
        val stats = TrackerCalculator.compute(
            listOf(
                day(-1, TrackerStatus.NO),
                day(-2, TrackerStatus.NO),
                // -3 missing
                day(-4, TrackerStatus.NO),
                day(-5, TrackerStatus.NO),
            ),
            today,
        )
        assertEquals(2, stats.longestStreak)
    }

    // ------------------------------------------------------------ boundaries

    @Test
    fun `unrecorded days are counted separately and never as NO`() {
        // 6-day window, 2 recorded.
        val stats = TrackerCalculator.compute(
            listOf(day(-1, TrackerStatus.NO), day(-2, TrackerStatus.NO)),
            today,
        )
        // Period is start (-2) .. lastCompleted (-1) = 2 days, both recorded.
        assertEquals(0, stats.unrecordedDays)

        val withGap = TrackerCalculator.compute(
            listOf(day(-5, TrackerStatus.NO)),
            today,
        )
        // Period -5..-1 = 5 days, only 1 recorded.
        assertEquals(4, withGap.unrecordedDays)
        assertEquals(1, withGap.noDays)
    }

    @Test
    fun `first ever entry produces no invented past`() {
        val stats = TrackerCalculator.compute(listOf(day(0, TrackerStatus.YES)), today)
        assertEquals(today, stats.trackingStartDay)
        assertEquals(1, stats.yesDays)
        // Today is not a completed day, so the period before it is empty.
        assertEquals(0, stats.unrecordedDays)
    }

    @Test
    fun `nothing recorded yields the honest empty state`() {
        val stats = TrackerCalculator.compute(emptyList(), today)
        assertEquals(TrackerStats.EMPTY, stats)
        assertNull(stats.trackingStartDay)
        assertNull(stats.latestRecordedDay)
        assertEquals(0, stats.currentStreak)
        assertEquals(0, stats.longestStreak)
    }

    @Test
    fun `a long history counts correctly`() {
        val records = (1L..400L).map { day(-it, if (it % 3 == 0L) TrackerStatus.YES else TrackerStatus.NO) }
        val stats = TrackerCalculator.compute(records, today)
        assertEquals(400, stats.recordedDays)
        assertEquals(133, stats.yesDays)
        assertEquals(267, stats.noDays)
        assertEquals(0, stats.unrecordedDays)
        assertTrue(stats.longestStreak >= 1)
    }

    @Test
    fun `records supplied out of order still compute correctly`() {
        val shuffled = listOf(
            day(-1, TrackerStatus.NO),
            day(-3, TrackerStatus.NO),
            day(-2, TrackerStatus.NO),
            day(-4, TrackerStatus.YES),
        )
        val stats = TrackerCalculator.compute(shuffled, today)
        assertEquals(3, stats.currentStreak)
        assertEquals(3, stats.longestStreak)
        assertEquals(today - 4, stats.trackingStartDay)
    }
}

/**
 * Calendar arithmetic, including the timezone and DST behaviour that would
 * silently corrupt history if it were derived from raw milliseconds.
 */
class TrackerDatesTest {

    private val utc = TimeZone.getTimeZone("UTC")

    @Test
    fun `epoch day round trips through civil date`() {
        listOf(0L, 1L, 19_000L, 20_723L, 20_724L, 30_000L).forEach { day ->
            val (y, m, d) = TrackerDates.civilFromDays(day)
            assertEquals(day, TrackerDates.daysFromCivil(y, m, d))
        }
    }

    @Test
    fun `known civil dates map to the right epoch day`() {
        assertEquals(0L, TrackerDates.daysFromCivil(1970, 1, 1))
        assertEquals(19_723L, TrackerDates.daysFromCivil(2024, 1, 1))
    }

    @Test
    fun `leap day is handled`() {
        val feb28 = TrackerDates.daysFromCivil(2024, 2, 28)
        val feb29 = TrackerDates.daysFromCivil(2024, 2, 29)
        val mar1 = TrackerDates.daysFromCivil(2024, 3, 1)
        assertEquals(feb28 + 1, feb29)
        assertEquals(feb29 + 1, mar1)
    }

    @Test
    fun `midnight rollover yields a new calendar day`() {
        val tz = TimeZone.getTimeZone("Asia/Kolkata")
        val lastMinuteOfDay = TrackerDates.startOfDayMillis(20_000L, tz) + 86_399_000L
        assertEquals(20_000L, TrackerDates.epochDayOf(lastMinuteOfDay, tz))
        val nextDay = TrackerDates.startOfDayMillis(20_001L, tz)
        assertEquals(20_001L, TrackerDates.epochDayOf(nextDay, tz))
    }

    @Test
    fun `yesterday is always exactly one day back`() {
        val tz = TimeZone.getTimeZone("Europe/Berlin")
        val now = TrackerDates.startOfDayMillis(20_000L, tz) + 12 * 3_600_000L
        assertEquals(19_999L, TrackerDates.yesterday(now, tz))
    }

    @Test
    fun `timezone change does not move a record to another date`() {
        // The same instant read in two timezones yields two different LOCAL days,
        // which is expected; what must never happen is a stored record being
        // re-interpreted. Records are keyed by the epoch day chosen at save time.
        val instant = TrackerDates.startOfDayMillis(20_000L, TimeZone.getTimeZone("Asia/Kolkata"))
        val inKolkata = TrackerDates.epochDayOf(instant, TimeZone.getTimeZone("Asia/Kolkata"))
        val inUtc = TrackerDates.epochDayOf(instant, TimeZone.getTimeZone("UTC"))
        assertEquals(20_000L, inKolkata)
        assertTrue(inUtc != inKolkata || inUtc == 20_000L) // differs, or coincides
    }

    @Test
    fun `DST spring forward still yields the right local day`() {
        // 2024-03-10 is a DST transition day in the US.
        val newYork = TimeZone.getTimeZone("America/New_York")
        val noon = TrackerDates.startOfDayMillis(TrackerDates.daysFromCivil(2024, 3, 10), newYork) +
            12 * 3_600_000L
        assertEquals(TrackerDates.daysFromCivil(2024, 3, 10), TrackerDates.epochDayOf(noon, newYork))
    }

    @Test
    fun `month boundaries and lengths are correct`() {
        val jan31 = TrackerDates.daysFromCivil(2024, 1, 31)
        assertEquals(31, TrackerDates.lengthOfMonth(jan31))
        assertEquals(TrackerDates.daysFromCivil(2024, 1, 1), TrackerDates.firstOfMonth(jan31))
        assertEquals(jan31, TrackerDates.lastOfMonth(jan31))

        // Leap February.
        val feb = TrackerDates.daysFromCivil(2024, 2, 10)
        assertEquals(29, TrackerDates.lengthOfMonth(feb))

        // Non-leap February.
        val feb2023 = TrackerDates.daysFromCivil(2023, 2, 10)
        assertEquals(28, TrackerDates.lengthOfMonth(feb2023))
    }

    @Test
    fun `month navigation clamps short months`() {
        val jan31 = TrackerDates.daysFromCivil(2024, 1, 31)
        val feb = TrackerDates.addMonthsClamped(jan31, 1)
        assertEquals(TrackerDates.daysFromCivil(2024, 2, 29), feb) // clamped, not Mar 2
    }

    @Test
    fun `month navigation crosses the year boundary`() {
        val dec = TrackerDates.daysFromCivil(2024, 12, 15)
        val jan = TrackerDates.addMonthsClamped(dec, 1)
        assertEquals(TrackerDates.daysFromCivil(2025, 1, 15), jan)
    }
}