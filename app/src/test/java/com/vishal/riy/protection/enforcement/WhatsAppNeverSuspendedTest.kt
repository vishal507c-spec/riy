package com.vishal.riy.protection.enforcement

import com.vishal.riy.protection.policy.ProtectionState
import com.vishal.riy.protection.state.ProtectionSession
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Regression tests for the unintentional WhatsApp suspension.
 *
 * WhatsApp is explicitly visible to RIY through AndroidManifest.xml but is not
 * part of the restrictive allowlist, so the generic hardening sweep used to
 * suspend it. These tests pin the contract:
 *  - WhatsApp is NEVER hidden or suspended by the sweep;
 *  - a stale suspension left by an older build is repaired on apply;
 *  - the install-time fast path stays inert for WhatsApp;
 *  - reconciliation clears a stale suspension instead of reintroducing it;
 *  - release can still unsuspend WhatsApp;
 *  - every OTHER unauthorized app keeps being suspended.
 */
class WhatsAppNeverSuspendedTest {

    private val riyPackage = "com.vishal.riy"
    private val whatsApp = BlockedAppPolicy.WHATSAPP_PACKAGE

    private lateinit var dpm: FakeDevicePolicyBoundary

    private fun engineFor(device: FakePackageDiscoveryBoundary): AndroidEnforcementEngine {
        dpm = FakeDevicePolicyBoundary(deviceOwnerPackage = riyPackage)
        return AndroidEnforcementEngine(
            devicePolicy = dpm,
            discovery = device,
            appPolicyResolver = PackageManagerAppPolicyResolver(device, riyPackage),
            riyPackageName = riyPackage,
            clock = { NOW },
        )
    }

    private fun device(): FakePackageDiscoveryBoundary =
        fakeDevice(systemDialer = "com.device.dialer", inputMethod = "com.device.ime") {
            riy(riyPackage)
            phone()
            keyboard()
            whatsApp()
            terabox()
            ordinary(packageName = "com.sideload.sneaky")
        }

    private fun session(): ProtectionSession = ProtectionSession(
        sessionId = "session-1",
        state = ProtectionState.RESTRICTED,
        startTime = NOW,
        expiryTime = NOW + 7_200_000L,
        reason = "content_detected",
        policyVersion = 1,
    )

    private fun rebootedEngine(device: FakePackageDiscoveryBoundary): AndroidEnforcementEngine =
        AndroidEnforcementEngine(
            devicePolicy = dpm,
            discovery = device,
            appPolicyResolver = PackageManagerAppPolicyResolver(device, riyPackage),
            riyPackageName = riyPackage,
            clock = { NOW },
        )

    private fun suspendWhatsApp() {
        assertTrue(
            "fixture must be able to model a stale suspension",
            dpm.setPackagesSuspended(dpm.adminFixture(riyPackage), listOf(whatsApp), true),
        )
        assertTrue(whatsApp in dpm.rawSuspendedPackages())
    }

    // ---------------------------------------------------------------- sweep

    @Test
    fun `whatsapp is never suspended or hidden by the hardening sweep`() {
        val engine = engineFor(device())

        assertEquals(
            PolicyApplicationResult.APPLIED,
            engine.applyRestrictedPolicyWithResult(session(), LockTaskPolicy()),
        )

        assertFalse("WhatsApp must never be suspended", whatsApp in dpm.rawSuspendedPackages())
        assertFalse("WhatsApp must never be hidden", whatsApp in dpm.rawHiddenPackages())
        assertFalse(
            "WhatsApp stays outside the lock-task allowlist; only suspension is exempt",
            whatsApp in dpm.rawLockTaskPackages(),
        )
        assertTrue(
            "ordinary unauthorized apps must still be suspended",
            "com.sideload.sneaky" in dpm.rawSuspendedPackages(),
        )
        assertTrue(
            "TeraBox must still be hidden",
            "com.flextech.client.terabox" in dpm.rawHiddenPackages(),
        )
        assertTrue(
            "TeraBox must still be suspended",
            "com.flextech.client.terabox" in dpm.rawSuspendedPackages(),
        )
    }

    // -------------------------------------------------------- stale repair

    @Test
    fun `stale whatsapp suspension from an older build is cleared on apply`() {
        val engine = engineFor(device())
        suspendWhatsApp()

        assertEquals(
            PolicyApplicationResult.APPLIED,
            engine.applyRestrictedPolicyWithResult(session(), LockTaskPolicy()),
        )

        assertFalse(
            "applying the fixed policy must repair the already-suspended state",
            whatsApp in dpm.rawSuspendedPackages(),
        )
    }

    @Test
    fun `reconciliation clears a stale whatsapp suspension`() {
        val device = device()
        val engine = engineFor(device)
        engine.applyRestrictedPolicyWithResult(session(), LockTaskPolicy())

        // An external actor (or the old build) suspends WhatsApp afterwards.
        suspendWhatsApp()

        val rebooted = rebootedEngine(device)
        assertNotNull(rebooted.restoreExpectation(session(), LockTaskPolicy()))
        assertEquals(ReconciliationResult.VERIFIED, rebooted.reconcileWithResult())

        assertFalse(
            "reconciliation must repair, never reintroduce, the suspension",
            whatsApp in dpm.rawSuspendedPackages(),
        )
        assertTrue(
            "reconciliation must still keep ordinary unauthorized apps suspended",
            "com.sideload.sneaky" in dpm.rawSuspendedPackages(),
        )
    }

    // ------------------------------------------------- install-time fast path

    @Test
    fun `install-time fast path never suspends whatsapp`() {
        val device = device()
        val engine = engineFor(device)
        engine.applyRestrictedPolicyWithResult(session(), LockTaskPolicy())

        val rebooted = rebootedEngine(device)
        rebooted.restoreExpectation(session(), LockTaskPolicy())

        assertTrue(
            "the install-time path must report WhatsApp as needing no action",
            rebooted.hardenSinglePackage(whatsApp),
        )
        assertFalse(whatsApp in dpm.rawSuspendedPackages())
        assertFalse(whatsApp in dpm.rawHiddenPackages())
    }

    @Test
    fun `install-time fast path still suspends an ordinary unauthorized app`() {
        val device = device()
        val engine = engineFor(device)
        engine.applyRestrictedPolicyWithResult(session(), LockTaskPolicy())
        // Model the app arriving after the sweep: clear its existing suspension.
        dpm.simulateExternalUnhide("com.sideload.sneaky")

        val rebooted = rebootedEngine(device)
        rebooted.restoreExpectation(session(), LockTaskPolicy())

        assertTrue(rebooted.hardenSinglePackage("com.sideload.sneaky"))
        assertTrue("com.sideload.sneaky" in dpm.rawSuspendedPackages())
    }

    // ---------------------------------------------------------------- release

    @Test
    fun `release still unsuspends whatsapp if it is suspended externally`() {
        val engine = engineFor(device())
        engine.applyRestrictedPolicyWithResult(session(), LockTaskPolicy())
        suspendWhatsApp()

        assertEquals(
            PolicyApplicationResult.APPLIED,
            engine.restoreNormalPolicyWithResult(),
        )

        assertFalse(whatsApp in dpm.rawSuspendedPackages())
        assertFalse(whatsApp in dpm.rawHiddenPackages())
        assertTrue("Device Owner is preserved", dpm.isDeviceOwnerApp(riyPackage))
    }

    // ---------------------------------------------------------------- policy

    @Test
    fun `suspension exemption names whatsapp only`() {
        assertTrue(BlockedAppPolicy.isSuspensionExemptPackage("com.whatsapp"))
        assertFalse(BlockedAppPolicy.isSuspensionExemptPackage("com.flextech.client.terabox"))
        assertFalse(BlockedAppPolicy.isSuspensionExemptPackage("com.sideload.sneaky"))
        assertFalse(BlockedAppPolicy.isSuspensionExemptPackage("com.vishal.riy"))
    }

    private companion object {
        const val NOW = 1_000_000L
    }
}
