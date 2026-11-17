package com.vishal.riy.tracker

import org.junit.Assert.assertEquals
import org.junit.Test

/** Pins the honesty contract: insights are counts of real answers only. */
class TrackerInsightsTest {

    private val recent = listOf(
        TrackerStatus.NO, TrackerStatus.NO, null, TrackerStatus.YES,
        TrackerStatus.NO, TrackerStatus.NO, TrackerStatus.NO, null,
        TrackerStatus.YES, TrackerStatus.NO, TrackerStatus.NO, TrackerStatus.NO,
        TrackerStatus.NO, TrackerStatus.NO,
    )

    @Test
    fun bestRunCountsOnlyConsecutiveNoDays() {
        // 3, then a gap, then 5 — the longest single run is 5.
        assertEquals(5, TrackerInsights.bestRun(recent))
    }

    @Test
    fun allReturnsExactlyTenInsights() {
        val stats = TrackerStats.EMPTY.copy(
            noDays = 10,
            currentStreak = 3,
            longestStreak = 5,
        )
        assertEquals(10, TrackerInsights.all(stats, recent, TrackerStatus.NO).size)
    }

    @Test
    fun bestRunIsZeroWhenNoAnswerWasRecorded() {
        assertEquals(0, TrackerInsights.bestRun(listOf(null, TrackerStatus.YES, null)))
    }
}
