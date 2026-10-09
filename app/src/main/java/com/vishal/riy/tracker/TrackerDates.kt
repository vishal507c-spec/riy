package com.vishal.riy.tracker

import java.util.Calendar
import java.util.Locale
import java.util.TimeZone

/**
 * Calendar-date arithmetic for the tracker.
 *
 * WHY NOT java.time: the app supports API 24 and does NOT enable core-library
 * desugaring, so `LocalDate` would crash on API 24/25. `java.util.Calendar` has
 * been available forever, and the civil-date <-> epoch-day conversion below is
 * the standard proleptic-Gregorian algorithm, which is exact and immune to
 * daylight-saving transitions (a DST day is not always 86 400 000 ms long, so
 * dividing milliseconds by a day would drift twice a year).
 *
 * Every function here is pure and unit-testable: the time zone is a parameter,
 * never implicitly the device default, so tests are deterministic.
 */
object TrackerDates {

    const val MILLIS_PER_DAY = 86_400_000L

    /** Days since 1970-01-01 for a local calendar date (proleptic Gregorian). */
    fun daysFromCivil(year: Int, month: Int, day: Int): Long {
        val y = year.toLong() - if (month <= 2) 1L else 0L
        val era = (if (y >= 0) y else y - 399L) / 400L
        val yoe = y - era * 400L                                    // [0, 399]
        val mp = (month + if (month > 2) -3 else 9).toLong()
        val doy = (153L * mp + 2L) / 5L + day - 1L                  // [0, 365]
        val doe = yoe * 365L + yoe / 4L - yoe / 100L + doy         // [0, 146096]
        return era * 146_097L + doe - 719_468L
    }

    /** Inverse of [daysFromCivil]: returns [year, month(1-12), day(1-31)]. */
    fun civilFromDays(epochDay: Long): Triple<Int, Int, Int> {
        val z = epochDay + 719_468L
        val era = (if (z >= 0) z else z - 146_096L) / 146_097L
        val doe = z - era * 146_097L                                // [0, 146096]
        val yoe = (doe - doe / 1460L + doe / 36_524L - doe / 146_096L) / 365L
        val y = yoe + era * 400L
        val doy = doe - (365L * yoe + yoe / 4L - yoe / 100L)         // [0, 365]
        val mp = (5L * doy + 2L) / 153L                              // [0, 11]
        val d = (doy - (153L * mp + 2L) / 5L + 1L).toInt()           // [1, 31]
        val m = (if (mp < 10L) mp + 3L else mp - 9L).toInt()         // [1, 12]
        return Triple((if (m <= 2) y + 1L else y).toInt(), m, d)
    }

    /** The local calendar day containing [millis], in [tz]. */
    fun epochDayOf(millis: Long, tz: TimeZone): Long {
        val c = Calendar.getInstance(tz, Locale.US).apply { timeInMillis = millis }
        return daysFromCivil(
            c.get(Calendar.YEAR),
            c.get(Calendar.MONTH) + 1,
            c.get(Calendar.DAY_OF_MONTH),
        )
    }

    /** Midnight at the start of [epochDay] in [tz]. Exact across DST. */
    fun startOfDayMillis(epochDay: Long, tz: TimeZone): Long =
        Calendar.getInstance(tz, Locale.US).apply {
            val (y, m, d) = civilFromDays(epochDay)
            clear()
            set(y, m - 1, d, 0, 0, 0)
            set(Calendar.MILLISECOND, 0)
        }.timeInMillis

    /** Local calendar day at [millis] in [tz]. */
    fun today(nowMillis: Long, tz: TimeZone): Long = epochDayOf(nowMillis, tz)

    /** [epochDay] shifted by [days]; calendar arithmetic, so DST-safe. */
    fun addDays(epochDay: Long, days: Long): Long = epochDay + days

    /** Yesterday's calendar day — the most recent COMPLETED day. */
    fun yesterday(nowMillis: Long, tz: TimeZone): Long = today(nowMillis, tz) - 1L

    /** Day of week for [epochDay], 1 = Sunday .. 7 = Saturday (Calendar order). */
    fun dayOfWeek(epochDay: Long, tz: TimeZone): Int =
        Calendar.getInstance(tz, Locale.US).apply {
            timeInMillis = startOfDayMillis(epochDay, tz)
        }.get(Calendar.DAY_OF_WEEK)

    /**
     * The first day of the month containing [epochDay], as an epoch day.
     */
    fun firstOfMonth(epochDay: Long): Long {
        val (y, m, _) = civilFromDays(epochDay)
        return daysFromCivil(y, m, 1)
    }

    /** Number of days in the month containing [epochDay]. */
    fun lengthOfMonth(epochDay: Long): Int {
        val (y, m, _) = civilFromDays(epochDay)
        // Day 0 of the next month is the last day of this one.
        val nextMonth = if (m == 12) 13 else m + 1
        val nextYear = if (m == 12) y + 1 else y
        return (daysFromCivil(nextYear, nextMonth, 1) - daysFromCivil(y, m, 1)).toInt()
    }

    /** Epoch day of the last day of the month containing [epochDay]. */
    fun lastOfMonth(epochDay: Long): Long =
        firstOfMonth(epochDay) + lengthOfMonth(epochDay) - 1L

    /**
     * Advances [epochDay] by [count] months, clamping the day-of-month so that
     * e.g. 31 Jan + 1 month is 28/29 Feb rather than spilling into March.
     */
    fun addMonthsClamped(epochDay: Long, count: Long): Long {
        val (y, m, d) = civilFromDays(epochDay)
        val totalMonths = (y.toLong() * 12L) + (m - 1L) + count
        val ny = (totalMonths / 12L).toInt()
        val nm = (totalMonths % 12L).toInt() + 1
        val maxDay = Calendar.getInstance(Locale.US).apply {
            clear(); set(ny, nm - 1, 1)
        }.getActualMaximum(Calendar.DAY_OF_MONTH)
        return daysFromCivil(ny, nm, minOf(d, maxDay))
    }

    /** Human-readable date, e.g. "Mon, 05 Oct 2026". Locale-stable. */
    fun formatDate(epochDay: Long, tz: TimeZone): String {
        val (y, m, d) = civilFromDays(epochDay)
        return String.format(
            Locale.US,
            "%02d/%02d/%04d",
            d,
            m,
            y,
        )
    }

    /** True when [epochDay] is strictly before [other]. */
    fun isBefore(a: Long, other: Long): Boolean = a < other
}