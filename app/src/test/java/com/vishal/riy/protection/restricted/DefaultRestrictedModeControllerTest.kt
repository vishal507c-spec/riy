package com.vishal.riy.protection.restricted

import com.vishal.riy.lock.InMemoryLockStore
import com.vishal.riy.lock.LockEngine
import com.vishal.riy.lock.LockState
import com.vishal.riy.protection.enforcement.EnforcementEngine
import com.vishal.riy.protection.enforcement.EnforcementStatus
import com.vishal.riy.protection.enforcement.LockTaskPolicy
import com.vishal.riy.protection.policy.ProtectionState
import com.vishal.riy.protection.state.InMemoryProtectionStateStore
import com.vishal.riy.protection.state.ProtectionSession
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The restricted-mode lifecycle: persistence, delegation to the enforcement
 * engine, and recovery that defers to LockEngine's authoritative deadline.
 * Runs entirely on the JVM — no Android, no device.
 */
class DefaultRestrictedModeControllerTest {

    private lateinit var enforcement: RecordingEnforcementEngine
    private lateinit var store: InMemoryProtectionStateStore
    private lateinit var locks: InMemoryLockStore

    private val policy: LockTaskPolicy get() = LockTaskPolicy()

    /**
     * Builds a controller AND its shared dependencies. A second call models a
     * restarted process only when the caller reuses the same [store]/[locks].
     */
    private fun controller(now: () -> Long = { NOW }): DefaultRestrictedModeController {
        enforcement = RecordingEnforcementEngine()
        store = InMemoryProtectionStateStore()
        locks = InMemoryLockStore()
        return DefaultRestrictedModeController(enforcement, store, locks, now)
    }

    private fun restrictedSession(
        id: String = "session-1",
        start: Long = NOW,
        expiry: Long = NOW + 7_200_000L,
    ): ProtectionSession = ProtectionSession(
        sessionId = id,
        state = ProtectionState.RESTRICTED,
        startTime = start,
        expiryTime = expiry,
        reason = "content_detected",
        policyVersion = 1,
    )

    // ---------------------------------------------------------------- enter

    @Test
    fun `enter persists the session and delegates to the enforcement engine`() {
        val controller = controller()

        controller.enter(restrictedSession(), policy)

        assertEquals(restrictedSession(), store.current())
        assertEquals(1, enforcement.applied.size)
        assertEquals(restrictedSession(), enforcement.applied.first().first)
        assertEquals(policy, enforcement.applied.first().second)
    }

    // ----------------------------------------------------------------- exit

    @Test
    fun `exit restores normal policy and clears the persisted session`() {
        val controller = controller()
        controller.enter(restrictedSession(), policy)

        controller.exit()

        assertEquals(1, enforcement.restored)
        assertNull("session must not survive exit", store.current())
        assertNull(controller.currentSession())
    }

    @Test
    fun `exit is idempotent`() {
        val controller = controller()
        controller.enter(restrictedSession(), policy)

        controller.exit()
        controller.exit()

        assertEquals("one restoration, not two", 1, enforcement.restored)
    }

    // ------------------------------------------------------------- recover

    @Test
    fun `recovers nothing when no session was persisted`() {
        val controller = controller()

        assertNull(controller.recover())
        assertEquals(0, enforcement.applied.size)
    }

    @Test
    fun `recover re-applies a live session whose lock deadline is still active`() {
        val controller = controller()
        // The authoritative deadline (LockEngine) still has an hour to run.
        locks.saveState(LockState(lockEndEpochMillis = NOW + 3_600_000L))
        controller.enter(restrictedSession(), policy)

        // A controller built on the SAME store/locks is exactly what a
        // restarted process sees — nothing is carried over in memory.
        val restarted = DefaultRestrictedModeController(enforcement, store, locks, clock = { NOW })

        val recovered = restarted.recover()

        assertNotNull(recovered)
        assertEquals(restrictedSession(), recovered)
        assertTrue(
            "the persisted session is re-applied to the platform",
            enforcement.applied.size == 2,
        )
    }

    @Test
    fun `recover ends the session when the lock deadline has expired`() {
        val controller = controller()
        // The authoritative deadline (LockEngine) is what decides this — the
        // controller never computes a competing one.
        locks.saveState(LockState(lockEndEpochMillis = NOW - 1_000L))
        controller.enter(restrictedSession(expiry = NOW - 1_000L), policy)

        val recovered = controller.recover()

        assertNull("an expired session must not be restored", recovered)
        assertEquals("normal policy is restored instead", 1, enforcement.restored)
        assertNull(store.current())
    }

    @Test
    fun `recover ends the session when the persisted session has expired even if a lock lingers`() {
        val controller = controller()
        // A lock deadline is still live, but the SESSION's own window has
        // already closed (a genuinely time-boxed session in the past).
        locks.saveState(LockState(lockEndEpochMillis = NOW + 3_600_000L))
        controller.enter(restrictedSession(start = NOW - 7_200_000L, expiry = NOW - 1_000L), policy)

        assertNull(controller.recover())
        assertEquals(1, enforcement.restored)
    }

    @Test
    fun `recover ignores a non-restricted persisted session`() {
        val controller = controller()
        store.save(restrictedSession().copy(state = ProtectionState.SUSPICIOUS))

        assertNull(controller.recover())
        assertEquals(0, enforcement.applied.size)
        assertNull("a non-restricted session is cleared, not restored", store.current())
    }

    @Test
    fun `recover uses LockEngine as the sole deadline authority`() {
        val controller = controller()
        // No live lock at all: even a session that looks unexpired is not
        // restored, because LockEngine owns the deadline.
        locks.saveState(LockState.EMPTY)
        controller.enter(restrictedSession(), policy)

        assertNull(controller.recover())
        assertEquals(1, enforcement.restored)
    }

    // ------------------------------------------------------- currentSession

    @Test
    fun `currentSession reflects the persisted session`() {
        val controller = controller()
        assertNull(controller.currentSession())

        controller.enter(restrictedSession(id = "abc"), policy)

        assertEquals("abc", controller.currentSession()?.sessionId)
    }

    // ----------------------------------------------------------- LockEngine

    @Test
    fun `the two hour contract still lives in LockEngine, not the controller`() {
        assertEquals(7_200_000L, LockEngine.LOCK_DURATION_MS)
    }

    private companion object {
        const val NOW = 1_000_000L
    }
}

/** Records calls instead of touching any platform; used by every test above. */
private class RecordingEnforcementEngine : EnforcementEngine {

    val applied = mutableListOf<Pair<ProtectionSession, LockTaskPolicy>>()
    var restored = 0

    override fun applyRestrictedPolicy(session: ProtectionSession, lockTaskPolicy: LockTaskPolicy) {
        applied += session to lockTaskPolicy
    }

    override fun restoreNormalPolicy() {
        restored++
    }

    override fun reconcile(): EnforcementStatus = EnforcementStatus.NORMAL

    override fun currentStatus(): EnforcementStatus = EnforcementStatus.NORMAL
}
