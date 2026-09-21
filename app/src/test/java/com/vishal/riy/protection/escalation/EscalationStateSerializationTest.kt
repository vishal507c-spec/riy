package com.vishal.riy.protection.escalation

import com.vishal.riy.protection.policy.ProtectionPolicy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Round-trips the escalation encoding WITHOUT a device: a persisted record must
 * survive exactly, and a corrupted one must be dropped rather than
 * half-restored — the same deterministic-corruption rule the session and lock
 * serialisations already apply.
 */
class EscalationStateSerializationTest {

    @Test
    fun `an empty escalation state round-trips`() {
        val state = EscalationState.NONE

        assertEquals(state, EscalationStateSerialization.decode(encode(state)))
    }

    @Test
    fun `a state with several timestamps round-trips in order`() {
        val state = EscalationState(
            eventTimestamps = listOf(1_000L, 2_000L, 3_000L),
            policyVersion = ProtectionPolicy.CURRENT_POLICY_VERSION,
        )

        val restored = EscalationStateSerialization.decode(encode(state))

        assertEquals(state, restored)
        assertEquals(listOf(1_000L, 2_000L, 3_000L), restored?.eventTimestamps)
    }

    @Test
    fun `a threshold-reaching state round-trips and still escalates`() {
        val state = EscalationState(eventTimestamps = listOf(10L, 20L, 30L))
        val restored = EscalationStateSerialization.decode(encode(state))!!

        val store = InMemoryEscalationStore().apply { save(restored) }
        val engine = ProtectionEscalationEngine(store, ProtectionPolicy()) { 30L }

        assertEquals(3, engine.current().qualifyingCount)
        assertTrue(engine.current().escalated)
    }

    @Test
    fun `blank and null input decodes to nothing`() {
        assertNull(EscalationStateSerialization.decode(null))
        assertNull(EscalationStateSerialization.decode(""))
        assertNull(EscalationStateSerialization.decode("   "))
    }

    @Test
    fun `truncated records are rejected rather than partially restored`() {
        assertNull(EscalationStateSerialization.decode("1"))
        assertNull(EscalationStateSerialization.decode("1|garbage"))
    }

    @Test
    fun `non-numeric fields are rejected rather than guessed`() {
        assertNull(EscalationStateSerialization.decode("not-a-number|1,2,3"))
        assertNull(EscalationStateSerialization.decode("1|not-a-millis,2"))
    }

    @Test
    fun `a corrupt record degrades to no escalation rather than a false one`() {
        // Decoding garbage yields NONE, so an engine built on it starts clean.
        val store = InMemoryEscalationStore().apply {
            save(EscalationStateSerialization.decode("garbage|corrupt") ?: EscalationState.NONE)
        }
        val engine = ProtectionEscalationEngine(store, ProtectionPolicy()) { NOW }

        assertEquals(0, engine.current().qualifyingCount)
        assertFalse(engine.current().escalated)
    }

    private companion object {
        const val NOW = 1_000_000L

        /** Encodes through the production serialization contract. */
        private fun encode(state: EscalationState): String =
            EscalationStateSerialization.encode(state)
    }
}
