package com.vishal.riy.tracker

/**
 * Everything the dashboard shows, derived purely from the stored records.
 *
 * Counters are counts of UNIQUE DATES, never of events: several events on one
 * date still produce exactly one YES day.
 */
data class TrackerStats(
    /** Unique dates confirmed YES. */
    val yesDays: Int,
    /** Unique dates confirmed NO. */
    val noDays: Int,
    /** Unique dates explicitly answered "not sure". */
    val unknownDays: Int,
    /** Days inside the tracking period with no record at all. */
    val unrecordedDays: Int,
    /** Consecutive confirmed-NO dates ending at the latest completed day. */
    val currentStreak: Int,
    /** Longest run of consecutive confirmed-NO dates ever recorded. */
    val longestStreak: Int,
    /** First date ever recorded, or null when nothing has been recorded yet. */
    val trackingStartDay: Long?,
    /** Most recent recorded date, or null. */
    val latestRecordedDay: Long?,
) {
    /** Total days carrying any answer (YES + NO + UNKNOWN). */
    val recordedDays: Int get() = yesDays + noDays + unknownDays

    companion object {
        /** The honest empty state — never a fabricated set of numbers. */
        val EMPTY = TrackerStats(
            yesDays = 0,
            noDays = 0,
            unknownDays = 0,
            unrecordedDays = 0,
            currentStreak = 0,
            longestStreak = 0,
            trackingStartDay = null,
            latestRecordedDay = null,
        )
    }
}

/**
 * THE calculation. Pure, so every rule below is directly unit-tested.
 *
 * Rules, stated explicitly because they are the whole specification:
 *
 *  1. Masturbation days = number of distinct dates whose answer is YES.
 *  2. Non-masturbation days = number of distinct dates whose answer is NO.
 *  3. An unrecorded day is NEVER counted as NO. It is counted separately in
 *     [TrackerStats.unrecordedDays] and it BREAKS a streak.
 *  4. UNKNOWN is a real answer that counts in [TrackerStats.unknownDays] but
 *     never as NO, and it breaks a streak.
 *  5. [TrackerStats.currentStreak] walks backwards from the latest COMPLETED
 *     day. "Completed" means strictly before the current local day: today is
 *     still in progress, so it is excluded until it has an answer of its own.
 *     If yesterday has no confirmed NO the streak is 0 — an unanswered day
 *     never counts as a day without masturbation.
 *  6. [TrackerStats.longestStreak] is the longest run of dates where each date
 *     is confirmed NO and the next date is exactly one day later. A gap ends the
 *     run; it is not bridged.
 *  7. Nothing is invented: only dates that actually have a record are counted,
 *     and days before the first recorded date are never included.
 */
object TrackerCalculator {

    fun compute(
        records: List<TrackerDay>,
        todayEpochDay: Long,
    ): TrackerStats {
        // Last write wins per date: this makes the calculation independent of
        // the order the caller happens to supply records in, and guarantees one
        // effective record per calendar date even if a list ever held two.
        val byDay = HashMap<Long, TrackerDay>(records.size.coerceAtLeast(1))
        records.forEach { record ->
            val existing = byDay[record.epochDay]
            if (existing == null || record.updatedAt >= existing.updatedAt) {
                byDay[record.epochDay] = record
            }
        }
        if (byDay.isEmpty()) return TrackerStats.EMPTY

        val yes = byDay.values.count { it.status == TrackerStatus.YES }
        val no = byDay.values.count { it.status == TrackerStatus.NO }
        val unknown = byDay.values.count { it.status == TrackerStatus.UNKNOWN }

        val start = byDay.keys.min()
        val latest = byDay.keys.max()
        val lastCompleted = todayEpochDay - 1L

        val unrecorded = countUnrecorded(byDay.keys, start, lastCompleted)
        val current = currentStreak(byDay, lastCompleted)
        val longest = longestStreak(
            byDay.values.filter { it.isConfirmedNo }.map { it.epochDay },
        )

        return TrackerStats(
            yesDays = yes,
            noDays = no,
            unknownDays = unknown,
            unrecordedDays = unrecorded,
            currentStreak = current,
            longestStreak = longest,
            trackingStartDay = start,
            latestRecordedDay = latest,
        )
    }

    /**
     * Days with no record inside [start..lastCompleted]. Clamped at zero so the
     * very first tracking day (where "yesterday" is before the start) cannot
     * produce a negative or absurd number.
     */
    private fun countUnrecorded(
        recorded: Set<Long>,
        start: Long,
        lastCompleted: Long,
    ): Int {
        if (lastCompleted < start) return 0
        var missing = 0L
        var day = start
        while (day <= lastCompleted) {
            if (day !in recorded) missing++
            day++
        }
        return missing.toInt()
    }

    /**
     * Consecutive confirmed-NO dates ending at [lastCompleted].
     *
     * Today is excluded by construction (its caller passes `today - 1`).
     */
    fun currentStreak(byDay: Map<Long, TrackerDay>, lastCompleted: Long): Int {
        var streak = 0
        var day = lastCompleted
        // Bounded so a corrupted record set can never spin forever.
        var guard = 0
        while (guard++ < MAX_STREAK_SCAN) {
            if (byDay[day]?.isConfirmedNo != true) break
            streak++
            day--
        }
        return streak
    }

    /**
     * Longest run of confirmed-NO dates on consecutive calendar dates.
     *
     * A gap ends the run — two NO days three days apart are NOT a two-day
     * streak, and an intervening unrecorded or YES day is never bridged.
     */
    fun longestStreak(noEpochDays: List<Long>): Int {
        if (noEpochDays.isEmpty()) return 0
        val sorted = noEpochDays.sorted()
        var best = 0
        var run = 0
        var previous = Long.MIN_VALUE
        for (day in sorted) {
            run = if (previous != Long.MIN_VALUE && day == previous + 1L) run + 1 else 1
            if (run > best) best = run
            previous = day
        }
        return best
    }

    private const val MAX_STREAK_SCAN = 200_000
}