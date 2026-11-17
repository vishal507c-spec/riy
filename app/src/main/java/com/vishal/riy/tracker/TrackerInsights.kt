package com.vishal.riy.tracker

import com.vishal.riy.R

/** Tone is visual only — never a health claim or a judgment about the user. */
enum class InsightTone { GOOD, NEUTRAL, QUIET }

data class TrackerInsight(
    val labelRes: Int,
    val value: String,
    val tone: InsightTone,
)

/**
 * Ten honest insights derived ONLY from stored answers. Every value is a count
 * of real dates; nothing is sampled, estimated, scored or graded. No percentages,
 * no levels, no "recovery stage" and no medical/health claim.
 */
object TrackerInsights {

    private val MILESTONES = listOf(7, 14, 30, 60, 90, 100, 180, 365)

    fun all(
        stats: TrackerStats,
        recent: List<TrackerStatus?>,
        today: TrackerStatus?,
    ): List<TrackerInsight> = listOf(
        TrackerInsight(
            R.string.tracker_insight_week_no,
            recent.takeLast(7).count { it == TrackerStatus.NO }.toString(),
            InsightTone.GOOD,
        ),
        TrackerInsight(
            R.string.tracker_insight_answered,
            "${recent.count { it != null }}/${recent.size.coerceAtLeast(14)}",
            InsightTone.QUIET,
        ),
        TrackerInsight(
            R.string.tracker_insight_best_run,
            bestRun(recent).toString(),
            InsightTone.GOOD,
        ),
        TrackerInsight(
            R.string.tracker_insight_current,
            stats.currentStreak.toString(),
            if (stats.currentStreak > 0) InsightTone.GOOD else InsightTone.QUIET,
        ),
        TrackerInsight(
            R.string.tracker_insight_longest,
            stats.longestStreak.toString(),
            InsightTone.GOOD,
        ),
        TrackerInsight(
            R.string.tracker_insight_recorded,
            stats.recordedDays.toString(),
            InsightTone.QUIET,
        ),
        TrackerInsight(
            R.string.tracker_insight_since_yes,
            since(recent, TrackerStatus.YES),
            InsightTone.QUIET,
        ),
        TrackerInsight(
            R.string.tracker_insight_since_no,
            since(recent, TrackerStatus.NO),
            InsightTone.QUIET,
        ),
        TrackerInsight(
            R.string.tracker_insight_milestone,
            nextMilestone(stats.currentStreak),
            InsightTone.NEUTRAL,
        ),
        TrackerInsight(
            R.string.tracker_insight_today,
            when (today) {
                TrackerStatus.NO -> "NO"
                TrackerStatus.YES -> "YES"
                TrackerStatus.UNKNOWN -> "?"
                null -> "—"
            },
            if (today == TrackerStatus.NO) InsightTone.GOOD else InsightTone.QUIET,
        ),
    )

    /** Longest consecutive confirmed-NO run inside the recent window. */
    fun bestRun(recent: List<TrackerStatus?>): Int {
        var best = 0
        var run = 0
        recent.forEach { status ->
            if (status == TrackerStatus.NO) {
                run++
                if (run > best) best = run
            } else {
                run = 0
            }
        }
        return best
    }

    /** Calendar days ago of the most recent matching answer, within the window. */
    private fun since(recent: List<TrackerStatus?>, target: TrackerStatus): String {
        if (recent.isEmpty()) return "—"
        val index = recent.indexOfLast { it == target }
        if (index < 0) return "—"
        return (recent.lastIndex - index).toString()
    }

    private fun nextMilestone(currentStreak: Int): String {
        val next = MILESTONES.firstOrNull { it > currentStreak } ?: return "—"
        return (next - currentStreak).toString()
    }
}
