package com.vishal.riy.blocker

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Regression: verification must retry, but never forever. */
class VerificationBackoffTest {

    @Test
    fun `bounded retries with capped exponential delay`() {
        assertTrue(VerificationBackoff.shouldRetry(0))
        assertTrue(VerificationBackoff.shouldRetry(1))
        assertTrue(VerificationBackoff.shouldRetry(2))
        assertFalse("no unbounded retry loop", VerificationBackoff.shouldRetry(3))
        assertFalse(VerificationBackoff.shouldRetry(100))

        assertEquals(2_000L, VerificationBackoff.delayMs(0))
        assertEquals(4_000L, VerificationBackoff.delayMs(1))
        assertEquals(8_000L, VerificationBackoff.delayMs(2))
        assertEquals("delay is capped", VerificationBackoff.MAX_DELAY_MS, VerificationBackoff.delayMs(20))
        assertEquals("negative input cannot spin", 2_000L, VerificationBackoff.delayMs(-5))
    }
}
