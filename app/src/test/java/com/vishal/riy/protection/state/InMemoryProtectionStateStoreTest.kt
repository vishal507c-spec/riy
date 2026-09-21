package com.vishal.riy.protection.state

import com.vishal.riy.protection.policy.ProtectionPolicy
import com.vishal.riy.protection.policy.ProtectionState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The state store contract: whatever a persisted implementation does, an
 * in-memory double must behave identically from the perspective of the
 * engines, so survival across restarts can be reasoned about in pure JVM tests.
 */
class InMemoryProtectionStateStoreTest {

    private fun session(
        state: ProtectionState,
        startTime: Long = 1_000L,
        expiryTime: Long = startTime + ProtectionPolicy.DEFAULT_RESTRICTION_DURATION.inWholeMilliseconds,
    ) = ProtectionSession(
        sessionId = "s-1",
        state = state,
        startTime = startTime,
        expiryTime = expiryTime,
        reason = "content_detected",
        policyVersion = ProtectionPolicy.CURRENT_POLICY_VERSION,
    )

    @Test
    fun `a fresh store holds no session`() {
        val store = InMemoryProtectionStateStore()
        assertNull(store.load())
        assertNull(store.current())
    }

    @Test
    fun `save then load round-trips the session`() {
        val store = InMemoryProtectionStateStore()
        val saved = session(ProtectionState.RESTRICTED)

        store.save(saved)

        assertEquals(saved, store.load())
    }

    @Test
    fun `current reflects the last saved session`() {
        val store = InMemoryProtectionStateStore()
        store.save(session(ProtectionState.HARDENED))

        assertEquals(ProtectionState.HARDENED, store.current()?.state)
    }

    @Test
    fun `save overwrites the previous session`() {
        val store = InMemoryProtectionStateStore()
        store.save(session(ProtectionState.RESTRICTED, expiryTime = 2_000L))
        store.save(session(ProtectionState.RECOVERY, expiryTime = 3_000L))

        val loaded = store.load()
        assertEquals(ProtectionState.RECOVERY, loaded?.state)
        assertEquals(3_000L, loaded?.expiryTime)
    }

    @Test
    fun `clear removes the persisted session`() {
        val store = InMemoryProtectionStateStore()
        store.save(session(ProtectionState.RESTRICTED))
        store.clear()

        assertNull(store.load())
        assertNull(store.current())
    }

    @Test
    fun `two independent stores do not share state`() {
        val a = InMemoryProtectionStateStore()
        val b = InMemoryProtectionStateStore()
        a.save(session(ProtectionState.RESTRICTED))

        assertNull(b.load())
    }

    @Test
    fun `a session reports its own expiry deterministically`() {
        val live = session(ProtectionState.RESTRICTED, startTime = 100L, expiryTime = 200L)
        val expired = session(ProtectionState.RESTRICTED, startTime = 100L, expiryTime = 150L)

        assertEquals(false, live.isExpired(150L))
        assertEquals(true, expired.isExpired(200L))
    }
}
