package com.vishal.riy.awareness

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.Calendar
import java.util.TimeZone

/**
 * State-machine tests for the awareness flow: detection -> pause -> optional
 * trigger -> lock -> expiry, including escalation, the daily counter reset,
 * dedup of double-fired WebView events, and survival across an app restart
 * and a device reboot (both modelled as a fresh controller over the same
 * persisted store).
 */
class AwarenessControllerTest {

    private val utc: TimeZone = TimeZone.getTimeZone("UTC")
    private lateinit var store: InMemoryAwarenessLockStore
    private var now: Long = utcMillis(2026, 9, 16, 12, 0)
    private lateinit var controller: AwarenessController

    @Before
    fun setUp() {
        store = InMemoryAwarenessLockStore()
        controller = AwarenessController(store, utc) { now }
    }

    @Test
    fun `a fresh start is unlocked with no detections`() {
        assertFalse(controller.isLocked())
        assertEquals(Phase.NONE, controller.snapshot().phase)
        assertEquals(0, controller.snapshot().detectionNumberToday)
    }

    @Test
    fun `first detection starts the pause and applies the two hour lock immediately`() {
        controller.onAdultSearchDetected(URL_A)

        val snap = controller.snapshot()
        assertEquals(Phase.PAUSE, snap.phase)
        assertEquals(1, snap.detectionNumberToday)
        assertTrue(controller.isLocked())
        assertEquals(2 * 3_600_000L, controller.remainingMillis())
        // the lock was persisted the instant the detection landed
        assertTrue(store.state.lockEndEpochMillis > 0L)
        assertEquals(1, store.metrics.adultSearchesAvoided)
        // the first pause is the calm opening, never shame
        assertEquals(AwarenessMessages.OPENING_TITLE, snap.message?.title)
    }

    @Test
    fun `duplicate events for the same search count once`() {
        // WebView can legitimately fire twice for one user action (intercept +
        // override); both must collapse into a single detection.
        controller.onAdultSearchDetected(URL_A)
        controller.onAdultSearchDetected(URL_A)
        assertEquals(1, controller.snapshot().detectionNumberToday)

        // after the dedup window, the same search is a genuine repeat
        now += 6_000
        controller.onAdultSearchDetected(URL_A)
        assertEquals(2, controller.snapshot().detectionNumberToday)
    }

    @Test
    fun `two different adult searches count twice and escalate`() {
        controller.onAdultSearchDetected(URL_A)
        assertEquals(2 * 3_600_000L, controller.remainingMillis())
        now += 10_000
        controller.onAdultSearchDetected(URL_B)
        assertEquals(4 * 3_600_000L, controller.remainingMillis())
    }

    @Test
    fun `progressive durations escalate across one day and cap at 24 hours`() {
        val hours = longArrayOf(2, 4, 8, 16, 24, 24)
        for (i in hours.indices) {
            now += 10_000
            controller.onAdultSearchDetected("https://www.google.com/search?q=adult-$i")
            assertEquals(hours[i] * 3_600_000L, controller.remainingMillis())
            assertEquals(i + 1, controller.snapshot().detectionNumberToday)
        }
    }

    @Test
    fun `the pause completes after its calm window and offers the optional trigger`() {
        controller.onAdultSearchDetected(URL_A)
        assertEquals(Phase.PAUSE, controller.snapshot().phase)

        now += 16_000 // pause is 15s
        controller.tick()

        assertEquals(Phase.TRIGGER, controller.snapshot().phase)
        assertEquals(1, store.metrics.awarenessPausesCompleted)
        // the lock is already live while the user picks a feeling
        assertTrue(controller.isLocked())
    }

    @Test
    fun `choosing a trigger keeps the lock and records it locally`() {
        reachTrigger()
        controller.onTriggerSelected(Trigger.LATE_NIGHT)

        assertEquals(Phase.LOCKED, controller.snapshot().phase)
        assertTrue(controller.isLocked())
        val records = store.loadTriggerRecords()
        assertEquals(1, records.size)
        assertEquals(Trigger.LATE_NIGHT.id, records[0].triggerId)
    }

    @Test
    fun `skipping the trigger is allowed and still keeps the lock`() {
        reachTrigger()
        controller.onTriggerSkipped()

        assertEquals(Phase.LOCKED, controller.snapshot().phase)
        assertTrue(controller.isLocked())
        assertEquals(0, store.loadTriggerRecords().size)
    }

    @Test
    fun `trigger selection outside the trigger phase is ignored`() {
        controller.onAdultSearchDetected(URL_A)
        controller.onTriggerSelected(Trigger.STRESS) // still in PAUSE
        assertEquals(Phase.PAUSE, controller.snapshot().phase)
    }

    @Test
    fun `the lock screen shows a countdown with no bypass`() {
        reachTrigger()
        controller.onTriggerSkipped()

        val snap = controller.snapshot()
        assertEquals(Phase.LOCKED, snap.phase)
        assertEquals(AwarenessMessages.LOCK_TITLE, snap.message?.title)
        assertEquals(AwarenessMessages.LOCK_HINT, snap.message?.body)
        // the 16s pause already consumed part of the 2h lock
        assertEquals(2 * 3_600_000L - 16_000, snap.remainingMillis)
        // remaining time keeps decreasing with the clock
        now += 10_000
        assertEquals(2 * 3_600_000L - 26_000, controller.snapshot().remainingMillis)
    }

    @Test
    fun `the lock expires only with time`() {
        controller.onAdultSearchDetected(URL_A)
        now += 16_000
        controller.tick()              // pause -> trigger
        controller.onTriggerSkipped()  // -> locked
        assertEquals(Phase.LOCKED, controller.snapshot().phase)
        assertTrue(controller.isLocked())

        now += 2 * 3_600_000L + 1_000  // past the 2h deadline
        controller.tick()

        assertEquals(Phase.NONE, controller.snapshot().phase)
        assertFalse(controller.isLocked())
    }

    @Test
    fun `an app restart keeps the lock and the remaining time accurate`() {
        controller.onAdultSearchDetected(URL_A)
        now += 42 * 60_000L

        // a fresh process reading the same persisted store
        val restarted = AwarenessController(store, utc) { now }
        assertTrue(restarted.isLocked())
        assertEquals(Phase.LOCKED, restarted.snapshot().phase)
        assertEquals(2 * 3_600_000L - 42 * 60_000L, restarted.remainingMillis())
        assertEquals(1, restarted.snapshot().detectionNumberToday)
    }

    @Test
    fun `a device reboot keeps the lock too`() {
        controller.onAdultSearchDetected(URL_A)
        now += 5 * 60_000L

        // reboot == fresh process + persisted store; the deadline is wall-clock
        val afterReboot = AwarenessController(store, utc) { now }
        assertTrue(afterReboot.isLocked())
        assertEquals(2 * 3_600_000L - 5 * 60_000L, afterReboot.remainingMillis())
    }

    @Test
    fun `an expired lock is cleared on a fresh start`() {
        controller.onAdultSearchDetected(URL_A)
        now += 3 * 3_600_000L

        val restarted = AwarenessController(store, utc) { now }
        assertFalse(restarted.isLocked())
        assertEquals(Phase.NONE, restarted.snapshot().phase)
    }

    @Test
    fun `new calendar day resets the detection counter`() {
        now = utcMillis(2026, 9, 16, 23, 0)
        controller.onAdultSearchDetected(URL_A) // 1st on Sep 16 -> 2h
        now = utcMillis(2026, 9, 17, 0, 30)
        controller.onAdultSearchDetected(URL_B) // next day -> resets to 2h

        val snap = controller.snapshot()
        assertEquals(1, snap.detectionNumberToday)
        assertEquals(2 * 3_600_000L, controller.remainingMillis())
    }

    @Test
    fun `a repeat within the day is met with kindness, not shame`() {
        controller.onAdultSearchDetected(URL_A)
        now += 10_000
        controller.onAdultSearchDetected(URL_B)

        assertEquals(AwarenessMessages.SLIP_TITLE, controller.snapshot().message?.title)
        assertEquals(AwarenessMessages.SLIP_BODY, controller.snapshot().message?.body)
    }

    @Test
    fun `the most common trigger over the last 7 days surfaces in the snapshot`() {
        reachTrigger()
        controller.onTriggerSelected(Trigger.LATE_NIGHT)
        reachTrigger()
        controller.onTriggerSelected(Trigger.LATE_NIGHT)
        reachTrigger()
        controller.onTriggerSelected(Trigger.STRESS)

        val top = controller.snapshot().topTrigger7d
        assertNotNull(top)
        assertEquals(Trigger.LATE_NIGHT, top)
    }

    private fun reachTrigger() {
        controller.onAdultSearchDetected(URL_A + System.nanoTime())
        now += 16_000
        controller.tick()
        assertEquals(Phase.TRIGGER, controller.snapshot().phase)
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

    private companion object {
        const val URL_A = "https://www.google.com/search?q=porn"
        const val URL_B = "https://www.google.com/search?q=hot+photo"
    }
}
