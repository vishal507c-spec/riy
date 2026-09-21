package com.vishal.riy.protection.state

import com.vishal.riy.protection.policy.ProtectionPolicy
import com.vishal.riy.protection.policy.ProtectionState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Round-trips the session encoding WITHOUT a device: a persisted record must
 * survive exactly, and a corrupted one must be dropped rather than
 * half-restored.
 */
class ProtectionSessionSerializationTest {

    @Test
    fun `a minimal restricted session round-trips`() {
        val session = ProtectionSession(
            sessionId = "session-1",
            state = ProtectionState.RESTRICTED,
            startTime = NOW,
            expiryTime = NOW + 7_200_000L,
            reason = "content_detected",
            policyVersion = ProtectionPolicy.CURRENT_POLICY_VERSION,
        )

        assertEquals(session, ProtectionSessionSerialization.decode(encode(session)))
    }

    @Test
    fun `a session with an allowlist round-trips in order`() {
        val session = ProtectionSession(
            sessionId = "session-2",
            state = ProtectionState.HARDENED,
            startTime = NOW,
            expiryTime = NOW + 7_200_000L,
            reason = "repeated_detections",
            policyVersion = 1,
            allowedPackages = listOf("com.vishal.riy", "com.device.dialer", "com.device.ime"),
            blockedCount = 187,
        )

        val restored = ProtectionSessionSerialization.decode(encode(session))

        assertEquals(session, restored)
        assertEquals(session.allowedPackages, restored?.allowedPackages)
        assertEquals(187, restored?.blockedCount)
    }

    @Test
    fun `an empty allowlist round-trips`() {
        val session = ProtectionSession(
            sessionId = "session-3",
            state = ProtectionState.RESTRICTED,
            startTime = NOW,
            expiryTime = NOW + 7_200_000L,
            reason = "content_detected",
            policyVersion = 1,
            allowedPackages = emptyList(),
        )

        assertEquals(session, ProtectionSessionSerialization.decode(encode(session)))
    }

    @Test
    fun `expiry semantics survive the round-trip`() {
        val live = ProtectionSession(
            sessionId = "s",
            state = ProtectionState.RESTRICTED,
            startTime = NOW,
            expiryTime = NOW + 1_000L,
            reason = "content_detected",
            policyVersion = 1,
        )
        val restored = ProtectionSessionSerialization.decode(encode(live))!!

        assertEquals(false, restored.isExpired(NOW))
        assertEquals(true, restored.isExpired(NOW + 1_000L))
    }

    @Test
    fun `blank and null input decodes to nothing`() {
        assertNull(ProtectionSessionSerialization.decode(null))
        assertNull(ProtectionSessionSerialization.decode(""))
        assertNull(ProtectionSessionSerialization.decode("   "))
    }

    @Test
    fun `truncated records are rejected rather than partially restored`() {
        assertNull(ProtectionSessionSerialization.decode("session-1|RESTRICTED|$NOW"))
        assertNull(ProtectionSessionSerialization.decode("session-1|RESTRICTED"))
    }

    @Test
    fun `non-numeric fields are rejected rather than guessed`() {
        val raw = "session-1|RESTRICTED|not-a-millis|$NOW|content_detected|1||0"
        assertNull(ProtectionSessionSerialization.decode(raw))
    }

    @Test
    fun `an unknown state name is rejected instead of falling back`() {
        val raw = "session-1|QUARANTINED|$NOW|$NOW|content_detected|1||0"
        assertNull(ProtectionSessionSerialization.decode(raw))
    }

    @Test
    fun `a separator inside a value cannot shift another field`() {
        val poisoned = ProtectionSession(
            sessionId = "a|b,c",
            state = ProtectionState.RESTRICTED,
            startTime = NOW,
            expiryTime = NOW + 7_200_000L,
            reason = "content|detected,too",
            policyVersion = 1,
            allowedPackages = listOf("com.vishal.riy"),
        )

        val restored = ProtectionSessionSerialization.decode(encode(poisoned))!!

        assertEquals("start/expiry/policyVersion must be read from the right fields", NOW, restored.startTime)
        assertEquals(NOW + 7_200_000L, restored.expiryTime)
        assertEquals(1, restored.policyVersion)
        assertEquals(ProtectionState.RESTRICTED, restored.state)
        assertEquals(listOf("com.vishal.riy"), restored.allowedPackages)
    }

    private companion object {
        const val NOW = 1_000_000L

        /** Encodes through the production [ProtectionStateStore] implementation. */
        private fun encode(session: ProtectionSession): String =
            ProtectionSessionSerialization.encode(session)
    }
}
