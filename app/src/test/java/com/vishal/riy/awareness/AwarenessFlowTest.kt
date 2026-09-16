package com.vishal.riy.awareness

import com.vishal.riy.blocker.SearchKeywordPolicy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.URLEncoder
import java.util.Calendar
import java.util.TimeZone

/**
 * End-to-end test of the contract the UI relies on: the EXISTING adult-search
 * policy decides what is an adult query, and only then does the awareness
 * flow begin. This proves the two headline safety properties — no false
 * detection, and existing blocking behaviour unchanged — alongside the
 * progressive lock schedule.
 */
class AwarenessFlowTest {

    private val utc: TimeZone = TimeZone.getTimeZone("UTC")
    private val store = InMemoryAwarenessLockStore()
    private var now: Long = utcMillis(2026, 9, 16, 12, 0)
    private val controller = AwarenessController(store, utc) { now }

    /** Mimics the browser layer: policy first, then (only on BLOCK) notify. */
    private fun search(query: String) {
        val url = "https://www.google.com/search?q=" + URLEncoder.encode(query, "UTF-8")
        if (SearchKeywordPolicy.evaluate(url) == SearchKeywordPolicy.Decision.BLOCK_ADULT_QUERY) {
            controller.onAdultSearchDetected(url)
        }
    }

    @Test
    fun `normal searches never start the awareness flow or a lock`() {
        search("how to tie a tie")
        search("hot weather")
        search("adult education")
        search("best coffee near me")
        search("sexton family history")
        search("essex county council")

        assertFalse(controller.isLocked())
        assertEquals(Phase.NONE, controller.snapshot().phase)
        assertEquals(0, store.metrics.adultSearchesAvoided)
    }

    @Test
    fun `an adult search is blocked and starts the pause plus a two hour lock`() {
        search("porn")

        assertEquals(Phase.PAUSE, controller.snapshot().phase)
        assertTrue(controller.isLocked())
        assertEquals(2 * 3_600_000L, controller.remainingMillis())
        assertEquals(1, store.metrics.adultSearchesAvoided)
    }

    @Test
    fun `repeated adult searches escalate exactly 2,4,8,16,24 hours`() {
        // distinct adult queries the existing policy is known to block
        val queries = listOf("porn", "hot photo", "nude girl", "xxx images", "sexy girl")
        val hours = longArrayOf(2, 4, 8, 16, 24)
        for (i in queries.indices) {
            now += 10_000
            search(queries[i])
            assertEquals(hours[i] * 3_600_000L, controller.remainingMillis())
        }
        // a sixth on the same day stays capped at 24h
        now += 10_000
        search("pornhub")
        assertEquals(24 * 3_600_000L, controller.remainingMillis())
    }

    @Test
    fun `normal searches between adult ones do not extend the lock`() {
        search("porn")
        now += 10_000
        search("weather forecast")
        search("pasta recipe")
        assertEquals(1, controller.snapshot().detectionNumberToday)
        // the lock keeps counting down from the single detection
        assertEquals(2 * 3_600_000L - 10_000, controller.remainingMillis())
    }

    @Test
    fun `the full flow reaches the lock screen and releases only when time runs out`() {
        search("porn")
        now += 16_000
        controller.tick()                      // pause -> trigger
        assertEquals(Phase.TRIGGER, controller.snapshot().phase)
        controller.onTriggerSkipped()          // optional trigger skipped
        assertEquals(Phase.LOCKED, controller.snapshot().phase)
        assertTrue(controller.isLocked())

        now += 2 * 3_600_000L + 1_000
        controller.tick()
        assertEquals(Phase.NONE, controller.snapshot().phase)
        assertFalse(controller.isLocked())
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
