package com.vishal.riy.awareness

import java.util.TimeZone

/** Where the user is in the awareness flow. */
enum class Phase { NONE, PAUSE, TRIGGER, LOCKED }

/** One calm line shown by the pause / lock UI. */
data class AwarenessMessage(val title: String, val body: String? = null)

/**
 * Immutable view the Compose UI renders; produced from the live clock so the
 * remaining-time countdown stays accurate.
 */
data class AwarenessSnapshot(
    val phase: Phase,
    val remainingMillis: Long,
    val pauseElapsedMillis: Long,
    val pauseDurationMillis: Long,
    val message: AwarenessMessage?,
    val detectionNumberToday: Int,
    val metrics: AwarenessMetrics,
    val topTrigger7d: Trigger?,
    val triggerOptions: List<Trigger> = Trigger.values().toList(),
) {
    val locked: Boolean get() = phase == Phase.LOCKED

    /** 0f..1f progress through the awareness pause, for a thin indicator. */
    val pauseFraction: Float
        get() = if (pauseDurationMillis > 0) {
            (pauseElapsedMillis.toFloat() / pauseDurationMillis.toFloat()).coerceIn(0f, 1f)
        } else 0f
}

/**
 * The awareness state machine. Pure Kotlin: it owns no Android object, takes
 * an injectable clock and timezone, and talks only to [AwarenessLockStore],
 * so the whole flow — escalation, daily reset, restart/reboot survival,
 * dedup of double-fired WebView events — is unit-testable on the JVM.
 *
 * Flow: adult search detected -> PAUSE (10-20s) -> optional TRIGGER choice ->
 * LOCKED (countdown, no in-app bypass). The lock deadline itself is persisted
 * the instant a detection lands, so the protection never depends on the UI or
 * the process staying alive.
 */
class AwarenessController(
    private val store: AwarenessLockStore,
    private val timeZone: TimeZone = TimeZone.getDefault(),
    private val clock: () -> Long = { System.currentTimeMillis() },
) {

    private var lockState: LockState =
        AwarenessLockEngine.clearIfExpired(store.loadState(), clock())
    private var metrics: AwarenessMetrics = store.loadMetrics()
    private var triggerRecords: List<TriggerStats.Record> = store.loadTriggerRecords()

    private var phase: Phase =
        if (AwarenessLockEngine.isLocked(lockState, clock())) Phase.LOCKED else Phase.NONE

    private var pauseStartMillis: Long = AwarenessLockEngine.NO_LOCK
    private var slipMode: Boolean = false

    // WebView can report the same blocked adult search twice for one user
    // action (intercept + override). A short same-URL window collapses those
    // into a single detection, while two genuinely different searches still
    // count twice.
    private var lastDetectionUrl: String? = null
    private var lastDetectionTime: Long = AwarenessLockEngine.NO_LOCK

    /** True while a lock is live right now (read from the persisted deadline). */
    fun isLocked(): Boolean = AwarenessLockEngine.isLocked(lockState, clock())

    /** Milliseconds left in the current lock. */
    fun remainingMillis(): Long = AwarenessLockEngine.remainingMillis(lockState, clock())

    /**
     * Records one adult-search detection. Idempotent for duplicate events of
     * the same URL within [DEDUP_WINDOW_MS]; otherwise escalates the lock per
     * the progressive schedule, persists it immediately, and starts the
     * awareness pause.
     */
    fun onAdultSearchDetected(url: String) {
        val now = clock()
        if (isDuplicateDetection(url, now)) return

        lockState = AwarenessLockEngine.onAdultSearchDetected(lockState, now, timeZone)
        store.saveState(lockState)

        metrics = metrics.copy(adultSearchesAvoided = metrics.adultSearchesAvoided + 1)
        store.saveMetrics(metrics)

        // A repeat within the day is a slip — never shame it, just stay kind.
        slipMode = lockState.detectionCount >= 2

        lastDetectionUrl = url
        lastDetectionTime = now
        pauseStartMillis = now
        phase = Phase.PAUSE
    }

    private fun isDuplicateDetection(url: String, now: Long): Boolean {
        if (lastDetectionUrl != url) return false
        return now - lastDetectionTime < DEDUP_WINDOW_MS
    }

    /**
     * Advances the flow on a tick: ends the pause once its calm window has
     * passed, and clears a lock once time has run out. The ViewModel calls
     * this roughly 4x/second to keep the countdown honest.
     */
    fun tick() {
        val now = clock()
        when (phase) {
            Phase.PAUSE -> if (now - pauseStartMillis >= PAUSE_DURATION_MS) {
                metrics = metrics.copy(awarenessPausesCompleted = metrics.awarenessPausesCompleted + 1)
                store.saveMetrics(metrics)
                phase = Phase.TRIGGER
            }

            Phase.TRIGGER, Phase.LOCKED -> if (!AwarenessLockEngine.isLocked(lockState, now)) {
                lockState = AwarenessLockEngine.clearIfExpired(lockState, now)
                store.saveState(lockState)
                phase = Phase.NONE
                pauseStartMillis = AwarenessLockEngine.NO_LOCK
            }

            Phase.NONE -> Unit
        }
    }

    /** User tagged a feeling (optional; stored locally, never uploaded). */
    fun onTriggerSelected(trigger: Trigger) {
        if (phase != Phase.TRIGGER) return
        triggerRecords = TriggerStats.append(
            triggerRecords,
            TriggerStats.Record(trigger.id, AwarenessLockEngine.dayKey(clock(), timeZone)),
        )
        store.saveTriggerRecords(triggerRecords)
        phase = Phase.LOCKED
    }

    /** Trigger selection is never forced — skipping just proceeds. */
    fun onTriggerSkipped() {
        if (phase == Phase.TRIGGER) phase = Phase.LOCKED
    }

    fun snapshot(): AwarenessSnapshot {
        val now = clock()
        return AwarenessSnapshot(
            phase = phase,
            remainingMillis = AwarenessLockEngine.remainingMillis(lockState, now),
            pauseElapsedMillis = if (phase == Phase.PAUSE && pauseStartMillis != AwarenessLockEngine.NO_LOCK) {
                now - pauseStartMillis
            } else 0L,
            pauseDurationMillis = PAUSE_DURATION_MS,
            message = messageForPhase(now),
            detectionNumberToday = lockState.detectionCount,
            metrics = metrics,
            topTrigger7d = TriggerStats.mostCommonInLast7Days(triggerRecords, now, timeZone),
        )
    }

    private fun messageForPhase(now: Long): AwarenessMessage? = when (phase) {
        Phase.PAUSE -> {
            val elapsed = now - pauseStartMillis
            when {
                elapsed < STAGE_ONE_MS && !slipMode ->
                    AwarenessMessage(AwarenessMessages.OPENING_TITLE, AwarenessMessages.OPENING_BODY)

                elapsed < STAGE_ONE_MS ->
                    AwarenessMessage(AwarenessMessages.SLIP_TITLE, AwarenessMessages.SLIP_BODY)

                elapsed < STAGE_TWO_MS ->
                    AwarenessMessage(AwarenessMessages.OBSERVE_TITLE, AwarenessMessages.OBSERVE_BODY)

                else -> {
                    val index = ((elapsed - STAGE_TWO_MS) / ROTATE_MS).toInt()
                        .coerceAtLeast(0) % AwarenessMessages.ROTATING.size
                    AwarenessMessage(AwarenessMessages.ROTATING[index])
                }
            }
        }

        Phase.LOCKED -> AwarenessMessage(
            AwarenessMessages.LOCK_TITLE,
            AwarenessMessages.LOCK_HINT,
        )

        Phase.TRIGGER, Phase.NONE -> null
    }

    /**
     * Re-reads everything from the store and recomputes the phase — the same
     * thing that happens when the process is restarted or the device reboots.
     * Used by tests to prove a lock survives both.
     */
    fun reloadFromStore() {
        lockState = AwarenessLockEngine.clearIfExpired(store.loadState(), clock())
        metrics = store.loadMetrics()
        triggerRecords = store.loadTriggerRecords()
        phase = if (AwarenessLockEngine.isLocked(lockState, clock())) Phase.LOCKED else Phase.NONE
        pauseStartMillis = AwarenessLockEngine.NO_LOCK
        slipMode = false
    }

    private companion object {
        /** 15 seconds: inside the spec's 10-20 second calm-pause window. */
        const val PAUSE_DURATION_MS = 15_000L
        const val STAGE_ONE_MS = 5_000L
        const val STAGE_TWO_MS = 10_000L
        const val ROTATE_MS = 4_000L
        const val DEDUP_WINDOW_MS = 5_000L
    }
}
