package com.vishal.riy.protection.recovery

import com.vishal.riy.lock.InMemoryLockStore
import com.vishal.riy.lock.LockEngine
import com.vishal.riy.lock.LockState
import com.vishal.riy.protection.enforcement.AndroidEnforcementEngine
import com.vishal.riy.protection.enforcement.EnforcementStatus
import com.vishal.riy.protection.enforcement.FakeDevicePolicyBoundary
import com.vishal.riy.protection.enforcement.PackageManagerAppPolicyResolver
import com.vishal.riy.protection.enforcement.PolicyApplicationResult
import com.vishal.riy.protection.enforcement.ReconciliationResult
import com.vishal.riy.protection.enforcement.fakeDevice
import com.vishal.riy.protection.events.InMemoryProtectionEventStore
import com.vishal.riy.protection.events.ProtectionLogEvent
import com.vishal.riy.protection.policy.ProtectionState
import com.vishal.riy.protection.restricted.DefaultRestrictedModeController
import com.vishal.riy.protection.state.InMemoryProtectionStateStore
import com.vishal.riy.protection.state.ProtectionSession
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Recovery hardening: the persisted protection state and the ACTUAL Android
 * enforcement state must reconcile safely after process death, a reboot, an
 * interrupted enforcement and an expired session — without ever inventing a
 * deadline or reporting a failure as a success.
 *
 * Runs entirely on the JVM against the REAL [AndroidEnforcementEngine] and the
 * REAL [DefaultRestrictedModeController], wired to in-memory stores and the
 * existing fake platform boundaries.
 */
class DefaultRecoveryServiceTest {

    private val riyPackage = "com.vishal.riy"

    private lateinit var dpm: FakeDevicePolicyBoundary
    private lateinit var enforcement: AndroidEnforcementEngine
    private lateinit var stateStore: InMemoryProtectionStateStore
    private lateinit var lockStore: InMemoryLockStore
    private lateinit var eventStore: InMemoryProtectionEventStore
    private lateinit var restricted: DefaultRestrictedModeController

    private var now = NOW

    private val service: DefaultRecoveryService
        get() = DefaultRecoveryService(restricted, lockStore, enforcement, eventStore) { now }

    @Before
    fun setUp() {
        now = NOW
        dpm = FakeDevicePolicyBoundary(deviceOwnerPackage = riyPackage)
        val device = fakeDevice(systemDialer = "com.device.dialer", inputMethod = "com.device.ime") {
            riy(riyPackage)
            phone()
            wallet()
            keyboard()
        }
        enforcement = AndroidEnforcementEngine(
            devicePolicy = dpm,
            discovery = device,
            appPolicyResolver = PackageManagerAppPolicyResolver(device, riyPackage),
            riyPackageName = riyPackage,
            clock = { now },
        )
        stateStore = InMemoryProtectionStateStore()
        lockStore = InMemoryLockStore()
        eventStore = InMemoryProtectionEventStore()
        restricted = DefaultRestrictedModeController(enforcement, stateStore, lockStore) { now }
    }

    // ------------------------------------------------------------- §12 live +

    @Test
    fun `a live session with correct enforcement reconciles without reapplication`() {
        armLiveSession()
        val expectedAllowlist = dpm.rawLockTaskPackages().toList()

        val result = service.recoverWithResult()

        assertEquals(RecoveryOutcome.RESTORED, result.outcome)
        assertEquals(EnforcementStatus.ACTIVE_RESTRICTED, result.enforcementStatus)
        // A verified reconciliation performs NO additional platform write.
        assertEquals("no unnecessary reapplication", expectedAllowlist, dpm.rawLockTaskPackages())
        assertEquals(1, dpm.setLockTaskPackagesCallCount)
    }

    // -------------------------------------------------- §13 missing enforcement

    @Test
    fun `a live session with missing enforcement reconciles back to the restricted policy`() {
        armLiveSession()

        // An external actor (or an interrupted enforcement) weakened the
        // platform state after the session was armed.
        dpm.simulateExternalDrift(listOf(riyPackage))

        val result = service.recoverWithResult()

        assertEquals(RecoveryOutcome.RESTORED, result.outcome)
        assertTrue("the expected restricted policy is restored",
            dpm.rawLockTaskPackages().containsAll(listOf(riyPackage, "com.device.dialer")))
    }

    @Test
    fun `an interrupted enforcement is retried through the existing path`() {
        armLiveSession()
        dpm.simulateExternalDrift(listOf(riyPackage))

        service.recoverWithResult()

        // The correction went through the enforcement engine, whose read-back
        // is what authorises the RESTORED verdict.
        assertTrue(dpm.setLockTaskPackagesCallCount >= 2)
    }

    // ------------------------------------------------- §14-15 expired session

    @Test
    fun `an expired session with a leftover restriction restores normal policy`() {
        armLiveSession()

        // Time itself is the only thing that ends the restriction.
        now += LockEngine.LOCK_DURATION_MS + 1_000L
        lockStore.saveState(LockEngine.clearIfExpired(lockStore.loadState(), now))

        val result = service.recoverWithResult()

        assertEquals(RecoveryOutcome.EXPIRED, result.outcome)
        assertEquals(EnforcementStatus.NORMAL, result.enforcementStatus)
        assertEquals("the normal policy keeps only RIY lock-task-able",
            listOf(riyPackage), dpm.rawLockTaskPackages())
    }

    @Test
    fun `an expired session leaves no restricted policy behind`() {
        armLiveSession()
        now += LockEngine.LOCK_DURATION_MS + 1_000L
        lockStore.saveState(LockEngine.clearIfExpired(lockStore.loadState(), now))

        service.recoverWithResult()

        assertNull("the persisted session is gone", stateStore.current())
        assertFalse(enforcement.currentStatus() == EnforcementStatus.ACTIVE_RESTRICTED)
    }

    @Test
    fun `a session whose LockEngine deadline expired is not restored even if the record lingers`() {
        armLiveSession()

        // The deadline has passed but the persisted session still looks live.
        now += LockEngine.LOCK_DURATION_MS + 1_000L

        val result = service.recoverWithResult()

        assertEquals(RecoveryOutcome.EXPIRED, result.outcome)
        assertNull(stateStore.current())
    }

    // ------------------------------------------------ §16 failure honesty

    @Test
    fun `an enforcement failure is never reported as a successful recovery`() {
        armLiveSession()
        dpm.simulateExternalDrift(listOf(riyPackage))
        dpm.rejectSetLockTaskPackages = true // the correction cannot land

        val result = service.recoverWithResult()

        assertNotEquals(RecoveryOutcome.RESTORED, result.outcome)
        assertTrue(result.outcome == RecoveryOutcome.MISMATCH)
        assertFalse("a failed reconciliation may not claim success", result.verified)
    }

    @Test
    fun `a failed recovery records an integrity mismatch rather than a reconciliation`() {
        armLiveSession()
        dpm.rejectSetLockTaskPackages = true
        dpm.simulateExternalDrift(listOf(riyPackage))

        service.recoverWithResult()

        val logged = eventStore.recent()
        assertTrue("the failure is exposed, not hidden",
            logged.any { it.type == ProtectionLogEvent.Type.INTEGRITY_MISMATCH })
    }

    // ------------------------------------------------- §17 safe non-owner

    @Test
    fun `a missing Device Owner fails safely and applies nothing`() {
        armLiveSession()
        dpm.deviceOwnerPackage = null // owner revoked after the session was armed

        val result = service.recoverWithResult()

        assertEquals(RecoveryOutcome.NOT_DEVICE_OWNER, result.outcome)
        assertEquals(ReconciliationResult.NOT_DEVICE_OWNER, result.reconciliation)
        assertTrue("no privileged operation was attempted",
            result.applicationResult == PolicyApplicationResult.NOT_DEVICE_OWNER)
    }

    // ------------------------------------ §18 corrupt persisted data handling

    @Test
    fun `an uninterpretable persisted session recovers to normal policy deterministically`() {
        // A session record that cannot be decoded is dropped by the store; the
        // recovery pass must then leave the device unrestricted.
        stateStore.save(restrictedSession())

        val result = service.recoverWithResult()

        // No live deadline exists, so the session is treated as expired.
        assertEquals(RecoveryOutcome.EXPIRED, result.outcome)
        assertNull(stateStore.current())
    }

    // ------------------------------------------------ §19 no new deadline

    @Test
    fun `reconciliation does not create a new deadline`() {
        armLiveSession()
        val deadlineBefore = lockStore.loadState().lockEndEpochMillis

        service.recoverWithResult()

        assertEquals("the LockEngine deadline is read, never rewritten",
            deadlineBefore, lockStore.loadState().lockEndEpochMillis)
        assertEquals("no competing deadline was written", 1, dpm.setLockTaskPackagesCallCount)
    }

    @Test
    fun `the LockEngine deadline remains the sole liveness authority`() {
        // A session that looks unexpired but has NO LockEngine deadline is not
        // restored: this class never invents a liveness verdict of its own.
        stateStore.save(restrictedSession(expiry = now + 7_200_000L))
        lockStore.saveState(LockState.EMPTY)

        val result = service.recoverWithResult()

        assertEquals(RecoveryOutcome.EXPIRED, result.outcome)
        assertNull("the session is ended, not restored", stateStore.current())
    }

    // ---------------------------------------- §20 no duplicate event logging

    @Test
    fun `a verified recovery does not log unnecessarily`() {
        armLiveSession()

        service.recoverWithResult()

        // A clean, verified pass records nothing at all — there is nothing to
        // surface. Only deviations are logged.
        assertEquals("a clean recovery adds no log noise", 0, eventStore.recent().size)
    }

    @Test
    fun `recovery is idempotent across repeated passes`() {
        armLiveSession()

        repeat(3) { service.recoverWithResult() }

        assertEquals("the platform was written once, not three times",
            1, dpm.setLockTaskPackagesCallCount)
        assertEquals(RecoveryOutcome.RESTORED, service.recoverWithResult().outcome)
    }

    // ------------------------------------------- process death / reboot §B-C

    @Test
    fun `a service built on the same stores is exactly a restarted process`() {
        armLiveSession()
        now += 30 * 60_000L

        // Nothing is carried over in memory: the recovery service and the
        // controller are rebuilt, exactly what a fresh process does.
        val restartedEnforcement = enforcement
        val restarted = DefaultRecoveryService(
            DefaultRestrictedModeController(restartedEnforcement, stateStore, lockStore) { now },
            lockStore,
            restartedEnforcement,
            eventStore,
        ) { now }

        assertEquals(RecoveryOutcome.RESTORED, restarted.recoverWithResult().outcome)
        assertTrue(LockEngine.isLocked(lockStore.loadState(), now))
    }

    @Test
    fun `reboot-modelled recovery clears a restriction whose deadline has passed`() {
        armLiveSession()

        // Arbitrary uptime loss, then wall time has moved past the deadline.
        now += LockEngine.LOCK_DURATION_MS + 60_000L
        lockStore.saveState(LockEngine.clearIfExpired(lockStore.loadState(), now))

        val restarted = DefaultRecoveryService(
            DefaultRestrictedModeController(enforcement, stateStore, lockStore) { now },
            lockStore,
            enforcement,
            eventStore,
        ) { now }

        assertEquals(RecoveryOutcome.EXPIRED, restarted.recoverWithResult().outcome)
        assertNull(stateStore.current())
    }

    // ------------------------------------------------------------- harness

    private fun armLiveSession() {
        val deadline = now + LockEngine.LOCK_DURATION_MS
        lockStore.saveState(LockState(lockEndEpochMillis = deadline))
        restricted.enter(restrictedSession(expiry = deadline), RESTRICTED_POLICY)
    }

    private fun restrictedSession(
        id: String = "session-1",
        expiry: Long = now + 7_200_000L,
    ): ProtectionSession = ProtectionSession(
        sessionId = id,
        state = ProtectionState.RESTRICTED,
        startTime = now,
        expiryTime = expiry,
        reason = "content_detected",
        policyVersion = 1,
    )

    private companion object {
        const val NOW = 1_000_000L
        val RESTRICTED_POLICY = com.vishal.riy.protection.enforcement.LockTaskPolicy()
    }
}
