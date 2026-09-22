package com.vishal.riy.protection.intelligence

/**
 * Durable storage for the OBSERVATION tier: the recent blocklist-matched
 * lookups the intelligence layer has seen but not yet necessarily acted on.
 *
 * The contract mirrors the existing stores in this codebase — it speaks only
 * pure-Kotlin types so the same contract backs a SharedPreferences
 * implementation in production and an [InMemoryObservationStore] in unit tests.
 *
 * SURVIVAL. Persisted observations must survive Activity recreation, process
 * death, an app restart and a normal device reboot. This is what makes the
 * correlation rules deterministic after a restart: a fresh intelligence layer
 * reading the SAME store reproduces exactly what a recovered process would,
 * so process death cannot silently erase an observation that was about to be
 * corroborated.
 *
 * SCOPE — deliberately narrow. This store holds ONLY observation records. It
 * persists NO protection state (that is
 * [com.vishal.riy.protection.state.ProtectionStateStore]), NO deadline (that is
 * [com.vishal.riy.lock.LockStore], the sole authority) and NO escalation count
 * (that is [com.vishal.riy.protection.escalation.ProtectionEscalationStore]).
 */
interface ObservationStore {

    /**
     * Every retained observation, oldest first. Implementations expire old
     * records at read time (see [ProtectionCorrelationWindow]) so the store
     * stays a bounded record of the recent past.
     */
    fun load(): List<SignalObservation>

    /**
     * Appends [observation], collapsing it into an existing record when the
     * same (domain, class) was already observed inside the dedup window — the
     * DNS layer's A/AAAA + retry burst for one host must count as ONE
     * observation. The EARLIEST timestamp is kept, so a later genuine repeat
     * still registers as a repeat.
     *
     * @return true when a NEW observation was recorded; false when it was
     *   absorbed as part of an existing one (in which case the caller treats it
     *   as no new information).
     */
    fun append(observation: SignalObservation): Boolean

    /** Removes every retained observation. Intended for diagnostics/tests. */
    fun clear()
}
