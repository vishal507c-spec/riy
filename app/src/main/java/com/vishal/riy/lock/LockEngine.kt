package com.vishal.riy.lock

/**
 * The 2-hour lock engine — pure Kotlin, no Android dependency, so every rule
 * below is unit-testable on the JVM.
 *
 * Contract:
 *  - One confirmed adult-content detection arms a lock of EXACTLY
 *    [LOCK_DURATION_MS] (2 hours) starting at the detection moment.
 *  - The lock is a wall-clock deadline persisted by [LockStore]; it therefore
 *    survives app restarts, force-stops and device reboots. Only the expiry
 *    of time clears it — there is no in-app bypass.
 *  - Repeated DNS queries for the SAME domain inside [DEDUP_WINDOW_MS]
 *    (a page load fires A + AAAA + retries within milliseconds) collapse into
 *    ONE detection. A genuinely different adult domain is a new detection and
 *    re-arms the 2-hour window from that moment.
 */
object LockEngine {

    /** Exactly 2 hours, in milliseconds. */
    const val LOCK_DURATION_MS: Long = 2L * 60L * 60L * 1_000L

    const val NO_LOCK: Long = 0L

    /**
     * Window in which repeated queries for the same domain count as one
     * detection (a single site visit resolves A/AAAA and retries rapidly).
     */
    const val DEDUP_WINDOW_MS: Long = 60_000L

    /**
     * Records one adult-content detection at [now] for [domain]. Idempotent
     * for duplicate queries of the same domain within [DEDUP_WINDOW_MS];
     * otherwise arms a fresh 2-hour lock ending at [now] + [LOCK_DURATION_MS].
     */
    fun onPornDetected(state: LockState, now: Long, domain: String): LockState {
        if (isDuplicate(state, domain, now)) return state
        return state.copy(
            lockEndEpochMillis = now + LOCK_DURATION_MS,
            lastDetectionEpochMillis = now,
            lastDomain = domain,
        )
    }

    /** Same domain queried again inside the dedup window is one detection. */
    private fun isDuplicate(state: LockState, domain: String, now: Long): Boolean {
        if (state.lastDomain != domain) return false
        if (state.lastDetectionEpochMillis == NO_LOCK) return false
        return now - state.lastDetectionEpochMillis < DEDUP_WINDOW_MS
    }

    /** True while the persisted lock deadline has not passed. */
    fun isLocked(state: LockState, epochMillis: Long): Boolean =
        state.lockEndEpochMillis != NO_LOCK && state.lockEndEpochMillis > epochMillis

    /** Milliseconds left in the current lock; 0 when there is no live lock. */
    fun remainingMillis(state: LockState, epochMillis: Long): Long =
        if (isLocked(state, epochMillis)) state.lockEndEpochMillis - epochMillis else 0L

    /**
     * Drops an already-expired lock so a fresh launch starts clean. A lock
     * that is still live is returned UNCHANGED (same instance) — it must keep
     * surviving restarts/reboots until time itself clears it. An already-clean
     * state is also returned unchanged, so persistence writes only happen on a
     * real transition.
     */
    fun clearIfExpired(state: LockState, epochMillis: Long): LockState {
        if (state.lockEndEpochMillis == NO_LOCK) return state
        if (state.lockEndEpochMillis > epochMillis) return state
        return state.copy(lockEndEpochMillis = NO_LOCK)
    }

    /** Formats a remaining-time duration as HH:MM:SS for the lock screen. */
    fun formatRemaining(millis: Long): String {
        val totalSeconds = (millis / 1_000L).coerceAtLeast(0L)
        val hours = totalSeconds / 3_600L
        val minutes = totalSeconds % 3_600L / 60L
        val seconds = totalSeconds % 60L
        return String.format(java.util.Locale.US, "%02d:%02d:%02d", hours, minutes, seconds)
    }

    /**
     * The same deadline, in the brief human form the Phase 10 UI shows large:
     * "1h 42m", or "42m" when under an hour. Minutes are never negative and
     * never shown as zero — the deadline this formats is always the
     * authoritative one from [remainingMillis], so this method stays a
     * formatter and owns no countdown of its own.
     */
    fun formatRemainingBrief(millis: Long): String {
        val totalMinutes = (millis / 60_000L).coerceAtLeast(0L)
        val hours = totalMinutes / 60L
        val minutes = totalMinutes % 60L
        return when {
            hours > 0L -> String.format(java.util.Locale.US, "%dh %02dm", hours, minutes)
            else -> String.format(java.util.Locale.US, "%dm", minutes.coerceAtLeast(1L))
        }
    }
}

/**
 * Persisted lock state. [LockStore] serialises it with [LockStateSerialization]
 * so a lock survives anything short of the deadline itself.
 */
data class LockState(
    val lockEndEpochMillis: Long = LockEngine.NO_LOCK,
    val lastDetectionEpochMillis: Long = LockEngine.NO_LOCK,
    val lastDomain: String? = null,
) {
    companion object {
        /** No lock has ever been recorded. */
        val EMPTY = LockState()
    }
}

/**
 * Delimited (de)serialisation so persistence round-trips are unit-testable
 * without Android. The domain is stored LAST and parsed with a limit so a
 * hypothetical '|' inside it could never shift the numeric fields.
 */
object LockStateSerialization {
    private const val SEP = "|"

    fun encode(state: LockState): String = buildString {
        append(state.lockEndEpochMillis).append(SEP)
        append(state.lastDetectionEpochMillis).append(SEP)
        append(state.lastDomain.orEmpty())
    }

    fun decode(raw: String?): LockState? {
        if (raw.isNullOrBlank()) return null
        val parts = raw.split(SEP, limit = 3)
        if (parts.size != 3) return null
        return try {
            LockState(
                lockEndEpochMillis = parts[0].toLong(),
                lastDetectionEpochMillis = parts[1].toLong(),
                lastDomain = parts[2].ifBlank { null },
            )
        } catch (_: NumberFormatException) {
            null
        }
    }
}
