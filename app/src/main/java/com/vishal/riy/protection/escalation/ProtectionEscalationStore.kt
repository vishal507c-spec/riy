package com.vishal.riy.protection.escalation

import com.vishal.riy.protection.policy.ProtectionPolicy

/**
 * Durable storage for the stateful part of escalation: the timestamps of the
 * qualifying protection events RIY has already counted.
 *
 * The store is platform-independent by design, exactly like the existing
 * [com.vishal.riy.protection.state.ProtectionStateStore]: it speaks only
 * pure-Kotlin types, so the same contract backs a SharedPreferences
 * implementation in production and an [InMemoryEscalationStore] in unit tests.
 *
 * SURVIVAL CONTRACT — persisted state must survive Activity recreation, the
 * process being killed, an app restart and a normal device reboot. This is
 * what makes escalation deterministic after a restart: the counting does not
 * restart from zero just because the RIY process died. A new engine reading
 * the SAME store instance is exactly what a fresh process does.
 *
 * SCOPE — this store holds ONLY escalation counting state. It deliberately
 * does NOT persist:
 *  - the protection session (that is [com.vishal.riy.protection.state.ProtectionStateStore]);
 *  - the 2-hour deadline (that is [com.vishal.riy.lock.LockStore], the sole
 *    deadline authority);
 *  - the current [com.vishal.riy.protection.policy.ProtectionState] — the
 *    policy engine remains the only state-transition authority.
 */
interface ProtectionEscalationStore {

    /**
     * The currently persisted escalation state, or [EscalationState.NONE] when
     * no qualifying event was ever recorded (or the record is unreadable).
     */
    fun load(): EscalationState

    /** Persists [state] atomically. */
    fun save(state: EscalationState)

    /** Removes any persisted escalation state. */
    fun clear()
}

/**
 * Immutable snapshot of what the escalation engine has counted so far. Pure
 * Kotlin, no Android reference.
 *
 * @param eventTimestamps epoch-millis of each qualifying
 *     [com.vishal.riy.protection.event.ProtectionEvidenceType.ADULT_DOMAIN_DNS_LOOKUP]
 *     the engine was asked to record, in the order they arrived. Only the
 *     timestamps inside the configured escalation window are ever counted, but
 *     the raw list is kept so the expiry rule is applied at read time rather
 *     than being baked into persistence (see [ProtectionEscalationEngine]).
 * @param policyVersion  the [ProtectionPolicy] that produced this state, so an
 *     inconsistent mix can be detected by the integrity engine.
 */
data class EscalationState(

    val eventTimestamps: List<Long> = emptyList(),

    val policyVersion: Int = ProtectionPolicy.CURRENT_POLICY_VERSION,

) {

    companion object {

        /** No qualifying event was ever recorded. */
        val NONE: EscalationState = EscalationState()
    }
}
