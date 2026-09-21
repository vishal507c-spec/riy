package com.vishal.riy.protection.ui

import com.vishal.riy.lock.InMemoryLockStore
import com.vishal.riy.lock.LockEngine
import com.vishal.riy.lock.LockState
import com.vishal.riy.protection.enforcement.EnforcementStatus
import com.vishal.riy.protection.events.InMemoryProtectionEventStore
import com.vishal.riy.protection.events.ProtectionLogEvent
import com.vishal.riy.protection.integrity.IntegrityStatus
import com.vishal.riy.protection.policy.ProtectionState
import com.vishal.riy.protection.state.InMemoryProtectionStateStore
import com.vishal.riy.protection.state.ProtectionSession
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * THE Phase 7 contract tests. Each one asserts one row of the
 * "AUTHORITATIVE BACKEND STATE → ProtectionStateBridge → ProtectionUiState →
 * Compose UI" data flow — and, critically, the rules that keep the UI from ever
 * becoming an authority:
 *
 *  - the bridge never writes a store and never computes a deadline;
 *  - a restricted session is shown only while LockEngine's deadline is live;
 *  - a restriction the platform did not confirm is never shown as protected;
 *  - a fresh instance reading the same persisted state reproduces the screen,
 *    which is exactly what process death and reboot must do.
 *
 * These use the SAME in-memory doubles the rest of the project uses, so no
 * Android framework object is involved.
 */
class DefaultProtectionStateBridgeTest {

    private val stateStore = InMemoryProtectionStateStore()
    private val lockStore = InMemoryLockStore()
    private val eventStore = InMemoryProtectionEventStore()
    private val enforcement = FakeEnforcementEngine()
    private val integrity = FakeIntegrityEngine(
        IntegrityStatus(
            deviceOwnerActive = true,
            uninstallProtectionActive = true,
            policyConsistent = true,
            sessionConsistent = true,
            lockTaskConsistent = true,
        ),
    )
    private val resolver = FakeAppPolicyResolver()

    private fun bridge(now: Long) = DefaultProtectionStateBridge(
        stateStore = stateStore,
        lockStore = lockStore,
        enforcement = enforcement,
        integrity = integrity,
        appPolicyResolver = resolver,
        eventStore = eventStore,
        clock = { now },
    )

    /**
     * Arms a real 2-hour LockEngine deadline and persists a matching session the
     * way the production pipeline does — LockEngine computes the deadline, the
     * session only mirrors it.
     */
    private fun armLiveSession(
        state: ProtectionState,
        now: Long,
        reason: String = "content_detected",
    ): ProtectionSession {
        val armed = LockEngine.onPornDetected(LockState.EMPTY, now, "example.test")
        lockStore.saveState(armed)
        val session = ProtectionSession(
            sessionId = "test-session",
            state = state,
            startTime = now,
            expiryTime = armed.lockEndEpochMillis,
            reason = reason,
            policyVersion = 1,
            allowedPackages = emptyList(),
        )
        stateStore.save(session)
        return session
    }

    // ------------------------------------------------------- 1. NORMAL screen

    @Test
    fun `NORMAL renders as the normal state`() {
        val now = System.currentTimeMillis()
        val ui = bridge(now).state.value

        assertEquals(ProtectionState.NORMAL, ui.protectionState)
        assertFalse(ui.isRestricted)
        assertFalse(ui.isRecovering)
        assertFalse(ui.enforcementMismatch)
    }

    @Test
    fun `NORMAL carries the live filtering phase, not a protection claim`() {
        val now = System.currentTimeMillis()
        // No session and no lock: the UI may only say filtering is off.
        val ui = bridge(now).state.value

        assertEquals(ProtectionState.NORMAL, ui.protectionState)
        assertEquals(0L, ui.remainingTime)
        assertEquals(0L, ui.expiresAt)
    }

    // ------------------------------------------------- 2. RESTRICTED screen

    @Test
    fun `RESTRICTED renders as the restricted state`() {
        val now = System.currentTimeMillis()
        armLiveSession(ProtectionState.RESTRICTED, now)
        enforcement.status = EnforcementStatus.ACTIVE_RESTRICTED

        val ui = bridge(now).state.value

        assertEquals(ProtectionState.RESTRICTED, ui.protectionState)
        assertTrue(ui.isRestricted)
        assertFalse(ui.isRecovering)
        assertTrue(ui.enforcementVerified)
        assertEquals(ProtectionState.RESTRICTED, ui.protectionState)
    }

    @Test
    fun `RESTRICTED exposes the allowed apps resolved from the real device`() {
        val now = System.currentTimeMillis()
        armLiveSession(ProtectionState.RESTRICTED, now)
        enforcement.status = EnforcementStatus.ACTIVE_RESTRICTED

        val ui = bridge(now).state.value

        assertEquals(resolver.allowed, ui.allowedApps)
    }

    // --------------------------------------------------- 3. HARDENED screen

    @Test
    fun `HARDENED renders as the hardened state`() {
        val now = System.currentTimeMillis()
        armLiveSession(ProtectionState.HARDENED, now, reason = "repeated_detections")
        enforcement.status = EnforcementStatus.ACTIVE_RESTRICTED

        val ui = bridge(now).state.value

        assertEquals(ProtectionState.HARDENED, ui.protectionState)
        assertTrue(ui.isRestricted)
        assertEquals("repeated_detections", ui.restrictedReason)
    }

    @Test
    fun `HARDENED reuses the SAME deadline as RESTRICTED — there is no second timer`() {
        val now = System.currentTimeMillis()

        // First detection: RESTRICTED with a 2-hour deadline.
        armLiveSession(ProtectionState.RESTRICTED, now)
        val restrictedExpiry = bridge(now).state.value.expiresAt
        assertEquals(LockEngine.LOCK_DURATION_MS, restrictedExpiry - now)

        // Escalation to HARDENED re-records the SAME wall-clock deadline, which
        // is exactly what ProtectionEventProcessor does. The bridge must report
        // it unchanged — never a second, extended or shortened window.
        val hardened = armLiveSession(ProtectionState.HARDENED, now, reason = "repeated_detections")
        stateStore.save(hardened.copy(startTime = now + 1_000L))

        val hardenedUi = bridge(now).state.value

        assertEquals(restrictedExpiry, hardenedUi.expiresAt)
        assertEquals(ProtectionState.HARDENED, hardenedUi.protectionState)
    }

    // --------------------------------------------- 4. RECOVERY / mismatch

    @Test
    fun `RECOVERY state renders as recovery`() {
        val now = System.currentTimeMillis()
        stateStore.save(
            ProtectionSession(
                sessionId = "recovery-session",
                state = ProtectionState.RECOVERY,
                startTime = now,
                expiryTime = now,
                reason = "boot_recovery",
                policyVersion = 1,
            ),
        )
        enforcement.status = EnforcementStatus.RECONCILING

        val ui = bridge(now).state.value

        assertTrue(ui.isRecovering)
        assertFalse(ui.enforcementMismatch)
    }

    @Test
    fun `a reconciling live session is recovery, not a mismatch and not protected`() {
        val now = System.currentTimeMillis()
        armLiveSession(ProtectionState.RESTRICTED, now)
        enforcement.status = EnforcementStatus.RECONCILING

        val ui = bridge(now).state.value

        assertTrue(ui.isRecovering)
        // Mid-reconciliation is NOT "verified" — the UI must not claim success.
        assertFalse(ui.enforcementVerified)
        // And it is not the terminal mismatch warning either.
        assertFalse(ui.enforcementMismatch)
    }

    @Test
    fun `enforcement failure is NOT shown as successfully protected`() {
        val now = System.currentTimeMillis()
        armLiveSession(ProtectionState.RESTRICTED, now)
        // The backend requires a restriction, but the platform says it is NOT
        // holding one. This must surface as a warning, never as "protected".
        enforcement.status = EnforcementStatus.NORMAL

        val ui = bridge(now).state.value

        assertTrue(ui.enforcementMismatch)
        assertFalse(ui.enforcementVerified)
        assertTrue(ui.isRestricted)
    }

    @Test
    fun `an unrestricted session is a mismatch when the platform is still restricted`() {
        val now = System.currentTimeMillis()
        stateStore.save(
            ProtectionSession(
                sessionId = "expired-session",
                state = ProtectionState.NORMAL,
                startTime = now - 10_000L,
                expiryTime = now - 5_000L,
                reason = "restriction_expired",
                policyVersion = 1,
            ),
        )
        enforcement.status = EnforcementStatus.ACTIVE_RESTRICTED

        val ui = bridge(now).state.value

        // No live deadline, so this is not a restricted UI state; but the
        // enforcement read-back disagrees with NORMAL, so it cannot be reported
        // as verified.
        assertEquals(ProtectionState.NORMAL, ui.protectionState)
        assertFalse(ui.enforcementVerified)
    }

    // ------------------------------------------- 5. countdown / deadline

    @Test
    fun `countdown derives from the authoritative LockEngine deadline`() {
        val now = 5_000_000L
        val armed = LockEngine.onPornDetected(LockState.EMPTY, now, "example.test")
        lockStore.saveState(armed)
        stateStore.save(
            ProtectionSession(
                sessionId = "s",
                state = ProtectionState.RESTRICTED,
                startTime = now,
                expiryTime = armed.lockEndEpochMillis,
                reason = "content_detected",
                policyVersion = 1,
            ),
        )
        enforcement.status = EnforcementStatus.ACTIVE_RESTRICTED

        val ui = bridge(now).state.value

        // remaining = authoritativeExpiry - now. Nothing more.
        assertEquals(armed.lockEndEpochMillis, ui.expiresAt)
        assertEquals(armed.lockEndEpochMillis - now, ui.remainingTime)
    }

    @Test
    fun `countdown ticks down by reading the deadline, never by computing a new one`() {
        val t0 = 5_000_000L
        val armed = LockEngine.onPornDetected(LockState.EMPTY, t0, "example.test")
        lockStore.saveState(armed)
        stateStore.save(
            ProtectionSession(
                sessionId = "s",
                state = ProtectionState.RESTRICTED,
                startTime = t0,
                expiryTime = armed.lockEndEpochMillis,
                reason = "content_detected",
                policyVersion = 1,
            ),
        )
        enforcement.status = EnforcementStatus.ACTIVE_RESTRICTED

        // A clock the test advances, exactly like LockController's injectable
        // clock. The deadline store is never touched by this.
        var clockNow = t0
        val b = DefaultProtectionStateBridge(
            stateStore = stateStore,
            lockStore = lockStore,
            enforcement = enforcement,
            integrity = integrity,
            appPolicyResolver = resolver,
            eventStore = eventStore,
            clock = { clockNow },
        )
        val first = b.state.value.remainingTime

        // Advance the clock only; the store deadline is untouched.
        clockNow = t0 + 30_000L
        b.refresh()
        val second = b.state.value.remainingTime

        assertEquals(first - 30_000L, second)
        // The authoritative deadline itself never moved.
        assertEquals(armed.lockEndEpochMillis, lockStore.loadState().lockEndEpochMillis)
    }

    @Test
    fun `the bridge never computes now plus duration and never writes the lock store`() {
        val now = System.currentTimeMillis()
        val b = bridge(now)
        val before = lockStore.loadState()

        repeat(5) { b.refresh() }

        // No deadline was armed, extended or cleared by the UI layer.
        assertEquals(before, lockStore.loadState())
        assertEquals(LockState.EMPTY, lockStore.loadState())
    }

    @Test
    fun `an expired deadline downgrades the UI to NORMAL even if a session lingers`() {
        val now = System.currentTimeMillis()
        val armed = LockEngine.onPornDetected(LockState.EMPTY, now - 3_600_000L, "example.test")
        // Already past the 2-hour window.
        lockStore.saveState(armed.copy(lockEndEpochMillis = now - 1_000L))
        stateStore.save(
            ProtectionSession(
                sessionId = "stale",
                state = ProtectionState.RESTRICTED,
                startTime = now - 3_600_000L,
                expiryTime = now - 1_000L,
                reason = "content_detected",
                policyVersion = 1,
            ),
        )
        enforcement.status = EnforcementStatus.NORMAL

        val ui = bridge(now).state.value

        // LockEngine's verdict — not the UI's — decides liveness.
        assertEquals(ProtectionState.NORMAL, ui.protectionState)
        assertFalse(ui.isRestricted)
        assertEquals(0L, ui.remainingTime)
    }

    // ----------------------------------------------- 6. process restart

    @Test
    fun `a lock armed with no session still renders as restricted`() {
        // Exactly how the instrumented LockScreenTest arms the lock, and how the
        // VPN service arms it in production: LockEngine's deadline alone.
        val now = System.currentTimeMillis()
        lockStore.saveState(LockEngine.onPornDetected(LockState.EMPTY, now, "example.test"))
        enforcement.status = EnforcementStatus.ACTIVE_RESTRICTED

        val ui = bridge(now).state.value

        assertEquals(ProtectionState.RESTRICTED, ui.protectionState)
        assertTrue(ui.isRestricted)
        // The countdown still comes from that same authoritative deadline.
        assertEquals(LockEngine.LOCK_DURATION_MS, ui.expiresAt - now)
    }

    @Test
    fun `a fresh instance reconstructs the same UI from persisted backend state`() {
        val now = System.currentTimeMillis()
        armLiveSession(ProtectionState.HARDENED, now, reason = "repeated_detections")
        enforcement.status = EnforcementStatus.ACTIVE_RESTRICTED

        // The first "process" renders, then dies. Nothing is cached anywhere but
        // the real persisted stores, which both instances share.
        val firstUi = bridge(now).state.value

        // A brand-new bridge reading the same stores must reproduce the state.
        val restarted = bridge(now)

        assertEquals(firstUi, restarted.state.value)
        assertEquals(ProtectionState.HARDENED, restarted.state.value.protectionState)
    }

    @Test
    fun `after the session is cleared the UI returns to NORMAL`() {
        val now = System.currentTimeMillis()
        armLiveSession(ProtectionState.RESTRICTED, now)
        enforcement.status = EnforcementStatus.ACTIVE_RESTRICTED
        val restricted = bridge(now).state.value
        assertTrue(restricted.isRestricted)

        // The backend ends the session (expiry / recovery) its own way.
        stateStore.clear()
        lockStore.saveState(LockState.EMPTY)
        enforcement.status = EnforcementStatus.NORMAL

        val ui = bridge(now).state.value

        assertEquals(ProtectionState.NORMAL, ui.protectionState)
        assertFalse(ui.isRestricted)
    }

    // ----------------------------------------- 7. honest failure handling

    @Test
    fun `an integrity read failure is reported as unverified, never as healthy`() {
        val now = System.currentTimeMillis()
        val b = DefaultProtectionStateBridge(
            stateStore = stateStore,
            lockStore = lockStore,
            enforcement = enforcement,
            integrity = FailingIntegrityEngine(),
            appPolicyResolver = resolver,
            eventStore = eventStore,
            clock = { now },
        )
        val ui = b.state.value

        assertFalse(ui.deviceOwnerActive)
        assertFalse(ui.uninstallProtectionActive)
        assertFalse(ui.integrityVerified)
    }

    @Test
    fun `an enforcement read failure falls back to reconciling, never to verified`() {
        val now = System.currentTimeMillis()
        armLiveSession(ProtectionState.RESTRICTED, now)
        val b = DefaultProtectionStateBridge(
            stateStore = stateStore,
            lockStore = lockStore,
            enforcement = FailingEnforcementEngine(),
            integrity = integrity,
            appPolicyResolver = resolver,
            eventStore = eventStore,
            clock = { now },
        )
        val ui = b.state.value

        assertTrue(ui.isRestricted)
        assertFalse(ui.enforcementVerified)
    }

    @Test
    fun `an allowlist resolution failure yields an empty list rather than a crash`() {
        val now = System.currentTimeMillis()
        armLiveSession(ProtectionState.RESTRICTED, now)
        enforcement.status = EnforcementStatus.ACTIVE_RESTRICTED
        val b = DefaultProtectionStateBridge(
            stateStore = stateStore,
            lockStore = lockStore,
            enforcement = enforcement,
            integrity = integrity,
            appPolicyResolver = FailingAppPolicyResolver(),
            eventStore = eventStore,
            clock = { now },
        )
        val ui = b.state.value

        assertEquals(emptyList<Any>(), ui.allowedApps)
    }

    // --------------------------------------- 8. recent events surface only

    @Test
    fun `recent events are surfaced for the status log without domains`() {
        val now = System.currentTimeMillis()
        eventStore.append(
            ProtectionLogEvent(
                eventId = "e1",
                type = ProtectionLogEvent.Type.RESTRICTION_ENTERED,
                timestamp = now,
                message = "restricted session entered",
            ),
        )
        armLiveSession(ProtectionState.RESTRICTED, now)
        enforcement.status = EnforcementStatus.ACTIVE_RESTRICTED

        val ui = bridge(now).state.value

        assertEquals(1, ui.recentEvents.size)
        assertEquals("restricted session entered", ui.recentEvents.single().message)
        // No private domain fact is carried into the UI state.
        ui.recentEvents.forEach { event ->
            assertFalse(event.message.contains("example.test"))
        }
    }

    // --------------------------------- 9. protection-active / filtering

    @Test
    fun `protectionActive reflects the live filtering phase only`() {
        val now = System.currentTimeMillis()
        // BlockerState is a real singleton driven by the VPN service; with no
        // service running it is OFF, so the UI must not claim filtering is live.
        val ui = bridge(now).state.value

        assertFalse(ui.protectionActive)
    }

    // ------------------------------------------------ 10. no write surface

    @Test
    fun `the bridge exposes no mutation of protection state`() {
        val now = System.currentTimeMillis()
        armLiveSession(ProtectionState.RESTRICTED, now)
        val ui = bridge(now).state.value

        // The state is a pure snapshot: there is nothing on it or on the bridge
        // that can transition protection, and the enforcement double was never
        // asked to apply or restore anything.
        assertEquals(0, enforcement.applyCount)
        assertEquals(0, enforcement.restoreCount)
        assertNotEquals(0, enforcement.currentStatusCount)
        assertTrue(ui.isRestricted)
    }

    @Test
    fun `LOADING state is the honest unknown, never a green light`() {
        val loading = ProtectionUiState.LOADING

        assertEquals(ProtectionState.NORMAL, loading.protectionState)
        assertFalse(loading.protectionActive)
        assertFalse(loading.integrityVerified)
        assertFalse(loading.deviceOwnerActive)
        assertFalse(loading.uninstallProtectionActive)
        assertFalse(loading.enforcementVerified)
    }
}
