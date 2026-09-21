package com.vishal.riy.protection.enforcement

import com.vishal.riy.protection.enforcement.platform.LockTaskFeatures
import com.vishal.riy.protection.policy.ProtectionState
import com.vishal.riy.protection.state.ProtectionSession
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The real enforcement engine, proven WITHOUT a device: every behaviour below
 * drives [AndroidEnforcementEngine] through the two fake boundaries, so no test
 * here needs a real `DevicePolicyManager`.
 *
 * What these tests guarantee:
 *  - Device Owner is verified before any privileged call;
 *  - the allowlist is computed from the modelled device and safety-checked;
 *  - success is only ever reported after a READ-BACK;
 *  - reconciliation compares expectation against actual platform state;
 *  - normal policy is restored without weakening any other protection.
 */
class AndroidEnforcementEngineTest {

    private val riyPackage = "com.vishal.riy"

    private lateinit var dpm: FakeDevicePolicyBoundary
    private lateinit var engine: AndroidEnforcementEngine

    private val defaultPolicy: LockTaskPolicy get() = LockTaskPolicy()

    // ------------------------------------------------------------- harness

    private fun engineFor(
        owner: Boolean = true,
        device: FakePackageDiscoveryBoundary = fullDevice(),
    ): AndroidEnforcementEngine {
        dpm = FakeDevicePolicyBoundary(deviceOwnerPackage = if (owner) riyPackage else null)
        return AndroidEnforcementEngine(
            devicePolicy = dpm,
            discovery = device,
            appPolicyResolver = PackageManagerAppPolicyResolver(device, riyPackage),
            riyPackageName = riyPackage,
            clock = { NOW },
        )
    }

    private fun fullDevice(): FakePackageDiscoveryBoundary =
        fakeDevice(systemDialer = "com.device.dialer", inputMethod = "com.device.ime") {
            riy(riyPackage)
            phone()
            wallet()
            keyboard()
        }

    private fun session(state: ProtectionState = ProtectionState.RESTRICTED): ProtectionSession =
        ProtectionSession(
            sessionId = "session-1",
            state = state,
            startTime = NOW,
            expiryTime = NOW + 7_200_000L,
            reason = "content_detected",
            policyVersion = 1,
        )

    // --------------------------------------------------- DEVICE OWNER GATE

    @Test
    fun `non device owner applies nothing and reports explicitly`() {
        engine = engineFor(owner = false)

        val result = engine.applyRestrictedPolicyWithResult(session(), defaultPolicy)

        assertEquals(PolicyApplicationResult.NOT_DEVICE_OWNER, result)
        assertEquals(ReconciliationResult.NOT_DEVICE_OWNER, engine.reconcileWithResult())
        assertTrue("no privileged write may happen without Device Owner", dpm.rawLockTaskPackages().isEmpty())
    }

    @Test
    fun `device owner applies the resolved allowlist`() {
        engine = engineFor()

        val result = engine.applyRestrictedPolicyWithResult(session(), defaultPolicy)

        assertEquals(PolicyApplicationResult.APPLIED, result)

        val allowlist = dpm.rawLockTaskPackages().toSet()
        assertTrue(riyPackage in allowlist)
        assertTrue("phone must be allowed", "com.device.dialer" in allowlist)
        assertTrue("wallet must be allowed", "com.wallet.pay" in allowlist)
        assertTrue("keyboard must be allowed", "com.device.ime" in allowlist)
    }

    // ------------------------------------------------- ALLOWLIST SAFETY §20

    @Test
    fun `missing riy rejects the allowlist and applies nothing`() {
        engine = engineFor(
            device = fakeDevice(systemDialer = "com.device.dialer", inputMethod = "com.device.ime") {
                riy(riyPackage, present = false)
                phone()
                wallet()
            },
        )

        val result = engine.applyRestrictedPolicyWithResult(session(), defaultPolicy)

        assertEquals(PolicyApplicationResult.REJECTED_UNSAFE_ALLOWLIST, result)
        assertTrue(dpm.rawLockTaskPackages().isEmpty())
    }

    @Test
    fun `missing phone rejects the allowlist and applies nothing`() {
        engine = engineFor(
            device = fakeDevice(systemDialer = null, inputMethod = "com.device.ime") {
                riy(riyPackage)
                wallet()
            },
        )

        val result = engine.applyRestrictedPolicyWithResult(session(), defaultPolicy)

        assertEquals(PolicyApplicationResult.REJECTED_UNSAFE_ALLOWLIST, result)
        assertTrue("refuses to restrict without a verified phone", dpm.rawLockTaskPackages().isEmpty())
    }

    @Test
    fun `missing wallet is not fatal — restriction still applies`() {
        engine = engineFor(
            device = fakeDevice(systemDialer = "com.device.dialer", inputMethod = "com.device.ime") {
                riy(riyPackage)
                phone()
                keyboard()
            },
        )

        val result = engine.applyRestrictedPolicyWithResult(session(), defaultPolicy)

        assertEquals(PolicyApplicationResult.APPLIED, result)
        assertNull(engine.diagnostics().walletPackage)
        assertTrue(
            "wallet unavailability must be recorded, not hidden",
            engine.diagnostics().issues.any { it.contains("Wallet unresolved") },
        )
    }

    // -------------------------------------------------- READ-BACK VERIFICATION

    @Test
    fun `a lying read-back is reported as a failure, not a success`() {
        engine = engineFor()
        dpm.corruptLockTaskPackagesOnRead = true

        val result = engine.applyRestrictedPolicyWithResult(session(), defaultPolicy)

        assertEquals(PolicyApplicationResult.FAILED, result)
        assertEquals(ReconciliationResult.MISMATCH, engine.lastReconciliationSnapshot())
    }

    @Test
    fun `a rejected platform write is reported as a failure`() {
        engine = engineFor()
        dpm.rejectSetLockTaskPackages = true

        val result = engine.applyRestrictedPolicyWithResult(session(), defaultPolicy)

        assertEquals(PolicyApplicationResult.FAILED, result)
    }

    @Test
    fun `applying the same session twice performs no second platform write`() {
        engine = engineFor()

        engine.applyRestrictedPolicyWithResult(session(), defaultPolicy)
        val second = engine.applyRestrictedPolicyWithResult(session(), defaultPolicy)

        assertEquals(PolicyApplicationResult.APPLIED, second)
        assertEquals("idempotent — one platform write, not two", 1, dpm.setLockTaskPackagesCallCount)
    }

    @Test
    fun `explicit policy packages are merged into the resolved allowlist`() {
        engine = engineFor()
        val policy = LockTaskPolicy(allowedPackages = listOf("com.explicitly.allowed"))

        val result = engine.applyRestrictedPolicyWithResult(session(), policy)

        assertEquals(PolicyApplicationResult.APPLIED, result)
        assertTrue("com.explicitly.allowed" in dpm.rawLockTaskPackages())
        assertTrue(riyPackage in dpm.rawLockTaskPackages())
    }

// ---------------------------------------------------- LOCK TASK FEATURES

    @Test
    fun `restrictive policy is platform conforming home enabled by Android invariant notifications implies home`() {
        engine = engineFor()

        engine.applyRestrictedPolicyWithResult(session(), defaultPolicy)

        val features: LockTaskFeatures = dpm.recordedLockTaskFeatures()
        assertTrue("home enabled by Android invariant (notifications -> home)", features.allowHome)
        assertFalse("overview stays blocked", features.allowOverview)
        assertTrue("notifications stay available", features.allowNotifications)
        assertTrue("system info stays available", features.allowSystemInfo)
        assertTrue("global actions (power/emergency) stay available", features.allowGlobalActions)
    }

    // -------------------------------------------------------- RECONCILIATION

    @Test
    fun `reconcile verifies immediately after a successful apply`() {
        engine = engineFor()
        engine.applyRestrictedPolicyWithResult(session(), defaultPolicy)

        assertEquals(ReconciliationResult.VERIFIED, engine.reconcileWithResult())
        assertEquals(EnforcementStatus.ACTIVE_RESTRICTED, engine.reconcile())
    }

    @Test
    fun `reconcile corrects a drifted allowlist and then verifies`() {
        engine = engineFor()
        engine.applyRestrictedPolicyWithResult(session(), defaultPolicy)

        // Simulate another actor (or a stale reboot state) widening the list.
        dpm.simulateExternalDrift(listOf(riyPackage, "com.device.dialer", "com.device.ime", "com.sneaky.app"))

        val result = engine.reconcileWithResult()

        assertEquals("self-heals then verifies on the SECOND read", ReconciliationResult.VERIFIED, result)
        assertFalse("the drift was corrected", "com.sneaky.app" in dpm.rawLockTaskPackages())
    }

    @Test
    fun `reconcile reports mismatch when correction is rejected`() {
        engine = engineFor()
        engine.applyRestrictedPolicyWithResult(session(), defaultPolicy)
        dpm.simulateExternalDrift(listOf("com.something.else"))
        dpm.rejectSetLockTaskPackages = true // correction cannot land

        assertEquals(ReconciliationResult.MISMATCH, engine.reconcileWithResult())
    }

    @Test
    fun `reconcile on a fresh engine expects the normal policy`() {
        engine = engineFor()

        // No session expected and a clean normal state → verified.
        assertEquals(ReconciliationResult.VERIFIED, engine.reconcileWithResult())
    }

    // ---------------------------------------------- API LEVEL CAPABILITY §9

    @Test
    fun `platforms without lock-task feature control still verify the allowlist`() {
        // Models a pre-API 28 device: features cannot be set or compared, but
        // the allowlist is still enforced and still verified honestly.
        engine = engineFor()
        dpm.lockTaskFeaturesSupported = false

        val result = engine.applyRestrictedPolicyWithResult(session(), defaultPolicy)

        assertEquals(PolicyApplicationResult.APPLIED, result)
        assertEquals(
            "reconciliation must succeed on what the platform CAN enforce",
            ReconciliationResult.VERIFIED,
            engine.reconcileWithResult(),
        )
    }

    @Test
    fun `platforms without feature control restore normal policy by allowlist only`() {
        engine = engineFor()
        dpm.lockTaskFeaturesSupported = false
        engine.applyRestrictedPolicyWithResult(session(), defaultPolicy)

        assertEquals(PolicyApplicationResult.APPLIED, engine.restoreNormalPolicyWithResult())
        assertEquals(listOf(riyPackage), dpm.rawLockTaskPackages())
    }

    // ---------------------------------------------------- NORMAL RESTORATION

    @Test
    fun `restoring normal policy removes the restriction without touching ownership`() {
        engine = engineFor()
        engine.applyRestrictedPolicyWithResult(session(), defaultPolicy)

        val restored = engine.restoreNormalPolicyWithResult()

        assertEquals(PolicyApplicationResult.APPLIED, restored)
        assertEquals(
            "normal keeps only RIY lock-task-able — no other app stays restricted",
            listOf(riyPackage),
            dpm.rawLockTaskPackages(),
        )
        // §22/§11 invariants: ownership and uninstall protection untouched.
        assertTrue(dpm.isDeviceOwnerApp(riyPackage))
        assertTrue(dpm.isUninstallBlocked(dpm.adminFixture(riyPackage), riyPackage))
    }

    @Test
    fun `current status reflects actual platform state`() {
        engine = engineFor()
        assertEquals(EnforcementStatus.NORMAL, engine.currentStatus())

        engine.applyRestrictedPolicyWithResult(session(), defaultPolicy)
        assertEquals(EnforcementStatus.ACTIVE_RESTRICTED, engine.currentStatus())

        engine.restoreNormalPolicyWithResult()
        assertEquals(EnforcementStatus.NORMAL, engine.currentStatus())
    }

    // ------------------------------------------------------------- §17 DIAGS

    @Test
    fun `diagnostics reports live state, including unresolved wallet`() {
        engine = engineFor(
            device = fakeDevice(systemDialer = "com.device.dialer", inputMethod = "com.device.ime") {
                riy(riyPackage)
                phone()
                keyboard()
            },
        )
        engine.applyRestrictedPolicyWithResult(session(), defaultPolicy)

        val report = engine.diagnostics()

        assertTrue(report.deviceOwner)
        assertEquals("$riyPackage/${ADMIN_CLASS}", report.adminComponent)
        assertTrue(report.riyPackagePresent)
        assertTrue(report.riyEnabled)
        assertTrue(report.uninstallProtected)
        assertEquals("com.device.dialer", report.phonePackage)
        assertNull(report.walletPackage)
        assertEquals("com.device.ime", report.inputMethodPackage)
        assertTrue(report.lockTaskAllowlist.contains(riyPackage))
        assertEquals(PolicyApplicationResult.APPLIED, report.applicationResult)
        assertEquals(ReconciliationResult.VERIFIED, report.reconciliation)
        assertTrue("report must be printable", report.toReportString().contains("RIY Enforcement Diagnostics"))
    }

    @Test
    fun `diagnostics on a non owner reports non owner honestly`() {
        engine = engineFor(owner = false)

        val report = engine.diagnostics()

        assertFalse(report.deviceOwner)
        assertFalse(report.adminActive)
        assertFalse(report.uninstallProtected)
    }

    private companion object {
        const val NOW = 1_000_000L
        const val ADMIN_CLASS = "com.vishal.riy.admin.RiyDeviceAdminReceiver"
    }
}
