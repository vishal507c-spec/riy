package com.vishal.riy.protection.intelligence

import com.vishal.riy.lock.InMemoryLockStore
import com.vishal.riy.lock.LockEngine
import com.vishal.riy.lock.LockState
import com.vishal.riy.protection.enforcement.EnforcementEngine
import com.vishal.riy.protection.enforcement.EnforcementStatus
import com.vishal.riy.protection.integrity.IntegrityIssue
import com.vishal.riy.protection.policy.ProtectionState
import com.vishal.riy.protection.state.InMemoryProtectionStateStore
import com.vishal.riy.protection.state.ProtectionSession
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * The self-healing pass: the persisted session, the authoritative LockEngine
 * deadline and the live platform enforcement must agree, a divergent mirror is
 * repaired and read back, and anything unverifiable is reported honestly —
 * never as success.
 */
class ProtectionConsistencyTest {

    private lateinit var stateStore: InMemoryProtectionStateStore
    private lateinit var lockStore: InMemoryLockStore
    private lateinit var enforcement: ScriptableEnforcement

    private var now = NOW

    @Before
    fun setUp() {
        now = NOW
        stateStore = InMemoryProtectionStateStore()
        lockStore = InMemoryLockStore()
        enforcement = ScriptableEnforcement()
    }

    @Test
    fun `a consistent live session verifies clean with no issues`() {
        armConsistentSession()

        val report = verify()

        assertTrue(report.consistent)
        assertTrue(report.issues.isEmpty())
    }

    @Test
    fun `a divergent session mirror is repaired to the authoritative LockEngine deadline`() {
        armConsistentSession()
        val authoritativeDeadline = lockStore.loadState().lockEndEpochMillis

        // Corrupt the MIRROR only: a much later expiry than the real deadline.
        stateStore.save(
            stateStore.current()!!.copy(expiryTime = authoritativeDeadline + 6 * 60 * MINUTE),
        )

        val report = verify()

        // LockEngine's deadline is untouched — the mirror could not move it.
        assertEquals(
            "the authoritative deadline is the one that was read back",
            authoritativeDeadline,
            lockStore.loadState().lockEndEpochMillis,
        )
        assertTrue(report.mirrorRepaired)
        // The session now mirrors the authoritative deadline again.
        assertEquals(authoritativeDeadline, stateStore.current()!!.expiryTime)
        // The repair is reported as INFO (the system self-corrected), and the
        // post-repair verdict is consistent.
        assertTrue(report.sessionConsistent)
        assertTrue(report.issues.any { it.severity == IntegrityIssue.Severity.INFO })
    }

    @Test
    fun `an unverifiable repair is reported as a mismatch, never as a success`() {
        armConsistentSession()
        val authoritativeDeadline = lockStore.loadState().lockEndEpochMillis
        stateStore.save(
            stateStore.current()!!.copy(expiryTime = authoritativeDeadline + 60 * MINUTE),
        )
        // The store refuses the repair write: a read-back cannot verify it.
        stateStore.refuseSave = true

        val report = verify()

        assertFalse("a failed repair is not reported as consistent", report.sessionConsistent)
        assertFalse(report.mirrorRepaired)
        assertEquals(authoritativeDeadline, lockStore.loadState().lockEndEpochMillis)
        assertTrue(report.issues.any { it.severity == IntegrityIssue.Severity.ERROR })
    }

    @Test
    fun `the deadline is never written from the consistency pass`() {
        armConsistentSession()
        val authoritativeDeadline = lockStore.loadState().lockEndEpochMillis

        verify()

        assertEquals(
            "the sole deadline authority was not modified",
            authoritativeDeadline,
            lockStore.loadState().lockEndEpochMillis,
        )
    }

    @Test
    fun `a session whose policy version is stale is reported inconsistent`() {
        armConsistentSession()
        stateStore.save(stateStore.current()!!.copy(policyVersion = 999))

        val report = verify()

        assertFalse(report.policyConsistent)
        assertTrue(report.issues.any { it.component == ProtectionConsistency.COMPONENT_SESSION })
    }

    @Test
    fun `an expired session with a leftover deadline is not treated as a live divergence`() {
        // No persisted session, but a leftover lock deadline (the normal
        // post-expiry transient): nothing to repair, nothing alarming.
        lockStore.saveState(LockState(lockEndEpochMillis = now - MINUTE))

        val report = verify()

        assertTrue(report.sessionConsistent)
        assertFalse(report.mirrorRepaired)
    }

    @Test
    fun `platform enforcement that disagrees with the backend is reported`() {
        armConsistentSession()
        enforcement.status = EnforcementStatus.NORMAL // backend requires restricted

        val report = verify()

        assertFalse(report.lockTaskConsistent)
        assertTrue(report.issues.any { it.component == ProtectionConsistency.COMPONENT_LOCK_TASK })
    }

    @Test
    fun `a failing enforcement read degrades to unverified rather than a green light`() {
        armConsistentSession()
        enforcement.throwOnRead = true

        val report = verify()

        assertFalse(report.lockTaskConsistent)
        assertFalse(report.consistent)
    }

    @Test
    fun `the state itself is never transitioned by a repair`() {
        armConsistentSession(state = ProtectionState.HARDENED)
        val deadline = lockStore.loadState().lockEndEpochMillis
        stateStore.save(stateStore.current()!!.copy(expiryTime = deadline + MINUTE))

        verify()

        assertEquals(
            "only the mirror changed; the protection state is untouched",
            ProtectionState.HARDENED,
            stateStore.current()!!.state,
        )
        assertEquals(deadline, stateStore.current()!!.expiryTime)
    }

    // ------------------------------------------------------------- helpers

    private fun verify(): ProtectionConsistency.Report = ProtectionConsistency(
        stateStore = stateStore,
        lockStore = lockStore,
        enforcement = enforcement,
        clock = { now },
    ).verify()

    private fun armConsistentSession(state: ProtectionState = ProtectionState.RESTRICTED) {
        val deadline = now + LockEngine.LOCK_DURATION_MS
        lockStore.saveState(LockState(lockEndEpochMillis = deadline))
        stateStore.save(
            ProtectionSession(
                sessionId = "session",
                state = state,
                startTime = now,
                expiryTime = deadline,
                reason = "content_detected",
                policyVersion = com.vishal.riy.protection.policy.ProtectionPolicy.CURRENT_POLICY_VERSION,
            ),
        )
        enforcement.status = EnforcementStatus.ACTIVE_RESTRICTED
    }

    private class ScriptableEnforcement : EnforcementEngine {

        var status: EnforcementStatus = EnforcementStatus.NORMAL

        var throwOnRead: Boolean = false

        override fun applyRestrictedPolicy(
            session: ProtectionSession,
            lockTaskPolicy: com.vishal.riy.protection.enforcement.LockTaskPolicy,
        ) = Unit

        override fun restoreNormalPolicy() = Unit

        override fun reconcile(): EnforcementStatus = status

        override fun currentStatus(): EnforcementStatus {
            if (throwOnRead) throw IllegalStateException("platform read failed")
            return status
        }
    }

    private companion object {
        const val NOW: Long = 1_700_000_000_000L
        const val MINUTE: Long = 60_000L
    }
}
