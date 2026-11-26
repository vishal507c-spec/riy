package com.vishal.riy.blocker

/**
 * Bounded retry policy for protection verification and recovery.
 *
 * The old code had two opposite defects: a self-test ran exactly once and was
 * never retried (so a single transient timeout left the shield "Not verified"
 * forever), while the watchdog restarted on a fixed cadence with no backoff.
 * This policy fixes both: a small, FINITE number of attempts with exponential
 * delays. It is pure Kotlin so the bounds are directly unit-testable.
 */
object VerificationBackoff {

    /** Number of retries AFTER the first attempt. Never more than this. */
    const val MAX_RETRIES = 3

    /** First retry delay. */
    const val BASE_DELAY_MS = 2_000L

    /** Upper bound so a late attempt can never sleep for minutes. */
    const val MAX_DELAY_MS = 8_000L

    /** True while another attempt is allowed. [attempt] is zero-based. */
    fun shouldRetry(attempt: Int): Boolean = attempt in 0 until MAX_RETRIES

    /**
     * Delay before retry [attempt]: 2s, 4s, 8s, then capped. A negative attempt
     * is treated as the first retry so a malformed caller can never produce a
     * zero/negative delay spin.
     */
    fun delayMs(attempt: Int): Long {
        val safe = attempt.coerceAtLeast(0)
        return minOf(MAX_DELAY_MS, BASE_DELAY_MS shl minOf(safe, 30))
    }
}
