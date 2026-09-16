package com.vishal.riy.awareness

import java.util.Calendar
import java.util.TimeZone

/**
 * The progressive lock engine — pure Kotlin, no Android dependency, so every
 * rule below is unit-testable on the JVM.
 *
 * Rules (see the feature spec):
 *  - 1st adult-search detection of a calendar day  ->  2 hour lock
 *  - 2nd                                          ->  4 hours
 *  - 3rd                                          ->  8 hours
 *  - 4th                                          -> 16 hours
 *  - 5th and every subsequent detection           -> 24 hours
 *  - The per-day counter resets on the next calendar day (device timezone).
 *
 * A lock is a wall-clock deadline persisted by [AwarenessLockStore]; it is
 * therefore immune to app restarts and device reboots. Only the expiry of
 * time clears it — there is no in-app bypass.
 */
object AwarenessLockEngine {

    /** Lock hours for detection #1..#5+ of a single calendar day. */
    val STEP_HOURS: IntArray = intArrayOf(2, 4, 8, 16, 24)

    const val NO_LOCK: Long = 0L

    private const val MILLIS_PER_HOUR = 3_600_000L

    /**
     * Lock duration for the [detectionNumberOfDay]-th detection of the day
     * (1-based): 1->2h, 2->4h, 3->8h, 4->16h, 5+->24h.
     */
    fun durationForDetection(detectionNumberOfDay: Int): Long {
        val index = (detectionNumberOfDay - 1).coerceAtLeast(0)
        val hours = STEP_HOURS[minOf(index, STEP_HOURS.size - 1)]
        return hours * MILLIS_PER_HOUR
    }

    /**
     * Calendar-day key of [epochMillis] in [timeZone] (yyyymmdd). Two instants
     * belong to the same local day iff their keys are equal. Production uses
     * the device timezone; tests pass a fixed zone so day boundaries — and
     * therefore the daily counter reset — are deterministic.
     */
    fun dayKey(epochMillis: Long, timeZone: TimeZone): Int {
        val calendar = Calendar.getInstance(timeZone)
        calendar.timeInMillis = epochMillis
        return calendar.get(Calendar.YEAR) * 10_000 +
            (calendar.get(Calendar.MONTH) + 1) * 100 +
            calendar.get(Calendar.DAY_OF_MONTH)
    }

    /** Day key [daysBack] calendar days before [epochMillis], in [timeZone]. */
    fun dayKeyDaysBack(epochMillis: Long, timeZone: TimeZone, daysBack: Int): Int {
        val calendar = Calendar.getInstance(timeZone)
        calendar.timeInMillis = epochMillis
        calendar.add(Calendar.DAY_OF_MONTH, -daysBack)
        return dayKey(calendar.timeInMillis, timeZone)
    }

    /**
     * Records one adult-search detection: the per-day counter resets
     * automatically when the detection lands on a new calendar day, and the
     * lock deadline is set to now + the duration for the new count.
     */
    fun onAdultSearchDetected(state: LockState, epochMillis: Long, timeZone: TimeZone): LockState {
        val today = dayKey(epochMillis, timeZone)
        val countToday = if (state.dayKey == today) state.detectionCount else 0
        val nextCount = countToday + 1
        return state.copy(
            dayKey = today,
            detectionCount = nextCount,
            lockEndEpochMillis = epochMillis + durationForDetection(nextCount),
            lastDetectionEpochMillis = epochMillis,
        )
    }

    /** True while the persisted lock deadline has not passed. */
    fun isLocked(state: LockState, epochMillis: Long): Boolean =
        state.lockEndEpochMillis != NO_LOCK && state.lockEndEpochMillis > epochMillis

    /** Milliseconds left in the current lock; 0 when there is no live lock. */
    fun remainingMillis(state: LockState, epochMillis: Long): Long =
        if (isLocked(state, epochMillis)) state.lockEndEpochMillis - epochMillis else 0L

    /**
     * Drops an already-expired lock so a fresh launch starts clean. A lock
     * that is still live is returned UNCHANGED — it must keep surviving
     * restarts/reboots until time itself clears it.
     */
    fun clearIfExpired(state: LockState, epochMillis: Long): LockState =
        if (isLocked(state, epochMillis)) state else state.copy(lockEndEpochMillis = NO_LOCK)

    /** Formats a remaining-time duration as HH:MM:SS for the lock screen. */
    fun formatRemaining(millis: Long): String {
        val totalSeconds = (millis / 1_000L).coerceAtLeast(0L)
        val hours = totalSeconds / 3_600L
        val minutes = totalSeconds % 3_600L / 60L
        val seconds = totalSeconds % 60L
        return String.format(java.util.Locale.US, "%02d:%02d:%02d", hours, minutes, seconds)
    }
}

/**
 * Persisted lock state. Everything the engine needs is here; the store
 * serialises it with [LockStateSerialization].
 */
data class LockState(
    val dayKey: Int = 0,
    val detectionCount: Int = 0,
    val lockEndEpochMillis: Long = AwarenessLockEngine.NO_LOCK,
    val lastDetectionEpochMillis: Long = AwarenessLockEngine.NO_LOCK,
) {
    companion object {
        /** No detection has ever been recorded. dayKey 0 can never collide with a real yyyymmdd. */
        val EMPTY = LockState()
    }
}

/** Delimited (de)serialisation so persistence round-trips are unit-testable without Android. */
object LockStateSerialization {
    private const val SEP = "|"

    fun encode(state: LockState): String = buildString {
        append(state.dayKey).append(SEP)
        append(state.detectionCount).append(SEP)
        append(state.lockEndEpochMillis).append(SEP)
        append(state.lastDetectionEpochMillis)
    }

    fun decode(raw: String?): LockState? {
        if (raw.isNullOrBlank()) return null
        val parts = raw.split(SEP)
        if (parts.size != 4) return null
        return try {
            LockState(
                dayKey = parts[0].toInt(),
                detectionCount = parts[1].toInt(),
                lockEndEpochMillis = parts[2].toLong(),
                lastDetectionEpochMillis = parts[3].toLong(),
            )
        } catch (_: NumberFormatException) {
            null
        }
    }
}
