package com.vishal.riy.lock

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Survival tests: a lock must outlive an app restart, a force-stop and a
 * device reboot, must clear exactly when its deadline passes, and must never
 * appear without a real detection. The lock is exercised exactly as production
 * does — the VPN service writes to the store, the UI reads it back — with a
 * controllable clock standing in for wall time.
 */
class LockSurvivalTest {

    private var clockNow = START

    private val store = InMemoryLockStore()

    private val controller = LockController(store, clock = { clockNow })

    @Test
    fun `no detection means no lock`() {
        controller.tick()
        assertFalse(controller.snapshot().locked)
        assertEquals(0L, controller.snapshot().remainingMillis)
    }

    @Test
    fun `service-armed lock is visible to the UI immediately`() {
        // The VPN service records a detection straight into the store.
        store.saveState(LockEngine.onPornDetected(store.loadState(), clockNow, "pornhub.com"))

        controller.tick()
        assertTrue(controller.snapshot().locked)
        assertEquals(2 * 3_600_000L, controller.snapshot().remainingMillis)
    }

    @Test
    fun `lock survives an app restart (fresh controller reading the same store)`() {
        store.saveState(LockEngine.onPornDetected(store.loadState(), clockNow, "pornhub.com"))

        // The process is killed and recreated: a brand-new controller reads the
        // persisted deadline and is still locked for the remaining time.
        clockNow += 30 * 60_000L
        val restarted = LockController(store, clock = { clockNow })
        assertTrue(restarted.isLocked())
        assertEquals(90 * 60_000L, restarted.remainingMillis())
    }

    @Test
    fun `lock survives a device reboot (deadline is wall-clock, not uptime)`() {
        store.saveState(LockEngine.onPornDetected(store.loadState(), clockNow, "pornhub.com"))

        // A reboot means arbitrary uptime loss; only wall time counts.
        clockNow += 45 * 60_000L
        val afterReboot = LockController(store, clock = { clockNow })
        assertTrue(afterReboot.isLocked())
        assertEquals(75 * 60_000L, afterReboot.remainingMillis())
    }

    @Test
    fun `expired lock clears on the tick after the deadline and protection returns to normal`() {
        store.saveState(LockEngine.onPornDetected(store.loadState(), clockNow, "pornhub.com"))

        // still locked one millisecond before the deadline
        clockNow = START + 2 * 3_600_000L - 1L
        controller.tick()
        assertTrue(controller.snapshot().locked)

        // the deadline passes; the next tick clears it and persists the clear
        clockNow = START + 2 * 3_600_000L
        controller.tick()
        assertFalse(controller.snapshot().locked)
        assertEquals(LockEngine.NO_LOCK, store.loadState().lockEndEpochMillis)

        // ...and stays cleared
        clockNow += 60_000L
        controller.tick()
        assertFalse(controller.snapshot().locked)
    }

    @Test
    fun `a detection recorded while the UI is backgrounded shows up on the next tick`() {
        controller.tick()
        assertFalse(controller.snapshot().locked)

        // service records a detection while the UI is not observing
        store.saveState(LockEngine.onPornDetected(store.loadState(), clockNow, "xvideos.com"))

        controller.tick()
        assertTrue(controller.snapshot().locked)
    }

    @Test
    fun `reloadFromStore restores the exact live deadline`() {
        store.saveState(LockEngine.onPornDetected(store.loadState(), clockNow, "pornhub.com"))
        clockNow += 10 * 60_000L

        controller.reloadFromStore()
        assertTrue(controller.isLocked())
        // the remaining time is measured from the ORIGINAL deadline
        assertEquals(110 * 60_000L, controller.remainingMillis())
        assertNotEquals(2 * 3_600_000L, controller.remainingMillis())
    }

    private companion object {
        const val START = 10_000_000_000L
    }
}
