package com.vishal.riy.lock

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Engine-level tests for the 2-hour lock: the exact duration, DNS-query dedup,
 * remaining-time accuracy, expiry, and persistence round-trip. Lock duration
 * is verified in pure milliseconds (no wall clock involved).
 */
class LockEngineTest {

    @Test
    fun `one detection locks for exactly 2 hours from the trigger moment`() {
        val now = 1_000_000L
        val state = LockEngine.onPornDetected(LockState.EMPTY, now, "pornhub.com")

        assertEquals(now + 2 * 3_600_000L, state.lockEndEpochMillis)
        assertTrue(LockEngine.isLocked(state, now))
        // exactly 2h remaining at the trigger instant
        assertEquals(2 * 3_600_000L, LockEngine.remainingMillis(state, now))
    }

    @Test
    fun `A and AAAA queries for the same domain within the window are one detection`() {
        val now = 1_000_000L
        val first = LockEngine.onPornDetected(LockState.EMPTY, now, "pornhub.com")
        // A few milliseconds later the browser also asks for the AAAA record
        val second = LockEngine.onPornDetected(first, now + 50L, "pornhub.com")

        assertSame(first, second) // dedup: same instance returned, no re-arm
        assertEquals(now + 2 * 3_600_000L, second.lockEndEpochMillis)
    }

    @Test
    fun `retries of the same domain within a minute do not extend the lock`() {
        var state = LockState.EMPTY
        val base = 1_000_000L
        state = LockEngine.onPornDetected(state, base, "xnxx.com")
        val deadline = state.lockEndEpochMillis

        for (i in 1..30) {
            state = LockEngine.onPornDetected(state, base + i * 1_000L, "xnxx.com")
            assertEquals(deadline, state.lockEndEpochMillis)
        }
    }

    @Test
    fun `a different adult domain within the window is a new detection and re-arms 2 hours`() {
        val first = 1_000_000L
        var state = LockEngine.onPornDetected(LockState.EMPTY, first, "pornhub.com")
        assertEquals(first + 2 * 3_600_000L, state.lockEndEpochMillis)

        // 30 seconds later the user heads to a different site
        val second = first + 30_000L
        state = LockEngine.onPornDetected(state, second, "xvideos.com")
        assertEquals(second + 2 * 3_600_000L, state.lockEndEpochMillis)
    }

    @Test
    fun `the same domain after the dedup window is a new detection`() {
        val first = 1_000_000L
        var state = LockEngine.onPornDetected(LockState.EMPTY, first, "pornhub.com")

        // beyond the 60s window
        state = LockEngine.onPornDetected(state, first + 61_000L, "pornhub.com")
        assertEquals(first + 61_000L + 2 * 3_600_000L, state.lockEndEpochMillis)
    }

    @Test
    fun `isLocked and remainingMillis are accurate across the whole lock window`() {
        val now = 1_000_000L
        val state = LockEngine.onPornDetected(LockState.EMPTY, now, "pornhub.com")

        assertTrue(LockEngine.isLocked(state, now))
        assertEquals(2 * 3_600_000L, LockEngine.remainingMillis(state, now))
        assertEquals(90 * 60_000L, LockEngine.remainingMillis(state, now + 30 * 60_000L))
        assertEquals(1_000L, LockEngine.remainingMillis(state, now + 2 * 3_600_000L - 1_000L))
        // the lock ends exactly at deadline — not one millisecond earlier
        assertEquals(0L, LockEngine.remainingMillis(state, now + 2 * 3_600_000L))
        assertFalse(LockEngine.isLocked(state, now + 2 * 3_600_000L))
    }

    @Test
    fun `clearIfExpired keeps a live lock untouched but drops an expired one`() {
        val now = 1_000_000L
        var state = LockEngine.onPornDetected(LockState.EMPTY, now, "pornhub.com")

        // a live lock must survive untouched (restart/reboot survival)
        state = LockEngine.clearIfExpired(state, now + 60_000L)
        assertTrue(LockEngine.isLocked(state, now + 60_000L))

        // once time runs out it is cleared
        state = LockEngine.clearIfExpired(state, now + 3 * 3_600_000L)
        assertFalse(LockEngine.isLocked(state, now + 3 * 3_600_000L))
        assertEquals(LockEngine.NO_LOCK, state.lockEndEpochMillis)
    }

    @Test
    fun `clearIfExpired on an already clean state does not churn persistence`() {
        // EMPTY has no lock, so it must be returned as-is (same instance)
        assertSame(LockState.EMPTY, LockEngine.clearIfExpired(LockState.EMPTY, 1_000_000L))

        val expired = LockState(
            lockEndEpochMillis = 500L,
            lastDetectionEpochMillis = 100L,
            lastDomain = "pornhub.com",
        )
        val cleared = LockEngine.clearIfExpired(expired, 1_000_000L)
        assertEquals(LockEngine.NO_LOCK, cleared.lockEndEpochMillis)
        // clearing again is idempotent: no lock -> same instance
        assertSame(cleared, LockEngine.clearIfExpired(cleared, 2_000_000L))
    }

    @Test
    fun `an empty state never reports a lock (no false lock on normal traffic)`() {
        assertFalse(LockEngine.isLocked(LockState.EMPTY, System.currentTimeMillis()))
        assertEquals(0L, LockEngine.remainingMillis(LockState.EMPTY, System.currentTimeMillis()))
    }

    @Test
    fun `remaining time formats as hours minutes seconds`() {
        assertEquals("00:00:00", LockEngine.formatRemaining(0L))
        assertEquals("00:00:00", LockEngine.formatRemaining(-1L))
        assertEquals("00:00:05", LockEngine.formatRemaining(5_000L))
        assertEquals("01:01:01", LockEngine.formatRemaining(3_661_000L))
        assertEquals("01:42:18", LockEngine.formatRemaining((1 * 3_600 + 42 * 60 + 18) * 1_000L))
        assertEquals("02:00:00", LockEngine.formatRemaining(2 * 3_600_000L))
    }

    @Test
    fun `lock state round-trips through persistence without losing the deadline`() {
        val now = 1_000_000L
        val state = LockEngine.onPornDetected(LockState.EMPTY, now, "pornhub.com")
        val restored = LockStateSerialization.decode(LockStateSerialization.encode(state))

        assertEquals(state, restored)
        assertTrue(LockEngine.isLocked(restored!!, now))
        // the persisted deadline is still live much later -> restart is not a bypass
        assertTrue(LockEngine.isLocked(restored, now + 119 * 60_000L))
        assertFalse(LockEngine.isLocked(restored, now + 121 * 60_000L))
    }

    @Test
    fun `a domain containing the separator still round-trips safely`() {
        val state = LockState(
            lockEndEpochMillis = 42L,
            lastDetectionEpochMillis = 7L,
            lastDomain = "weird|domain.example.com",
        )
        val restored = LockStateSerialization.decode(LockStateSerialization.encode(state))
        assertEquals(state, restored)
    }

    @Test
    fun `garbage persisted state decodes to nothing rather than a fake lock`() {
        assertEquals(null, LockStateSerialization.decode(null))
        assertEquals(null, LockStateSerialization.decode(""))
        assertEquals(null, LockStateSerialization.decode("garbage"))
        assertEquals(null, LockStateSerialization.decode("not-a-number|2|site.com"))
        assertEquals(LockState.EMPTY, LockStateSerialization.decode(LockStateSerialization.encode(LockState.EMPTY)))
    }

    @Test
    fun `dedup window is exactly 60 seconds`() {
        val base = 1_000_000L
        var state = LockEngine.onPornDetected(LockState.EMPTY, base, "pornhub.com")
        val deadline = state.lockEndEpochMillis

        // 59s later: still deduped
        state = LockEngine.onPornDetected(state, base + 59_000L, "pornhub.com")
        assertEquals("inside the window — no re-arm", deadline, state.lockEndEpochMillis)

        // 61s later: a fresh detection
        state = LockEngine.onPornDetected(state, base + 61_000L, "pornhub.com")
        assertNotEquals(deadline, state.lockEndEpochMillis)
    }

    private fun assertSame(expected: Any, actual: Any) {
        assertTrue("expected the same instance, got $actual", expected === actual)
    }
}
