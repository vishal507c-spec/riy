package com.vishal.riy.protection.enforcement

import com.vishal.riy.protection.enforcement.platform.DevicePolicyBoundary
import com.vishal.riy.protection.policy.ProtectionState
import com.vishal.riy.protection.state.ProtectionSession
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Layered-enforcement regression tests (acceptance A–D, H–I):
 *
 *  A. Telegram launches (in the restrictive allowlist) during protection.
 *  B. TeraBox cannot launch (absent from allowlist AND hidden+suspended).
 *  C. TeraBox installed mid-restriction is immediately neutralized.
 *  D. Arbitrary unauthorized apps are suspended (default-deny at package level).
 *  H. Telegram stays allowed / TeraBox stays blocked across reboot
 *     (restoreExpectation + reconcile path).
 *  I. RIY's own update is never neutralized.
 */
class TelegramAllowedBypassBlockedTest {

    private val riyPackage = "com.vishal.riy"
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

    private fun deviceWithTelegramAndTeraBox(): FakePackageDiscoveryBoundary =
        fakeDevice(systemDialer = "com.device.dialer", inputMethod = "com.device.ime") {
            riy(riyPackage)
            phone()
            keyboard()
            telegram()
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

    // ------------------------------------------------------------- TEST A

    @Test
    fun `testA telegram is allowed during protection`() {
        val device = deviceWithTelegramAndTeraBox()
        val resolver = PackageManagerAppPolicyResolver(device, riyPackage)

        val telegram = resolver.resolveAllowedApps()
            .firstOrNull { it.category == AllowedAppCategory.COMMUNICATION }

        assertTrue("Telegram must resolve as COMMUNICATION", telegram != null)
        assertEquals("org.telegram.messenger", telegram!!.packageName)

        val engine = engineFor(device)
        assertEquals(PolicyApplicationResult.APPLIED, engine.applyRestrictedPolicyWithResult(session(), LockTaskPolicy()))

        assertTrue(
            "Telegram must be in the restrictive lock-task allowlist",
            "org.telegram.messenger" in dpm.rawLockTaskPackages(),
        )
    }

    @Test
    fun `telegram absent is not fatal — restriction still applies`() {
        val device = fakeDevice(systemDialer = "com.device.dialer", inputMethod = "com.device.ime") {
            riy(riyPackage)
            phone()
            keyboard()
        }
        val engine = engineFor(device)
        assertEquals(PolicyApplicationResult.APPLIED, engine.applyRestrictedPolicyWithResult(session(), LockTaskPolicy()))
    }

    // ------------------------------------------------------------- TEST B

    @Test
    fun `testB terabox is blocked during protection (allowlist plus hide plus suspend)`() {
        val device = deviceWithTelegramAndTeraBox()
        val engine = engineFor(device)

        assertEquals(PolicyApplicationResult.APPLIED, engine.applyRestrictedPolicyWithResult(session(), LockTaskPolicy()))

        // Layer 1: default-deny — absent from the lock-task allowlist.
        assertFalse(
            "TeraBox must NOT be in the restrictive allowlist",
            "com.flextech.client.terabox" in dpm.rawLockTaskPackages(),
        )
        // Layer 2: hidden + suspended behind the Device Owner seat.
        assertTrue(
            "TeraBox must be hidden during protection",
            "com.flextech.client.terabox" in dpm.rawHiddenPackages(),
        )
        assertTrue(
            "TeraBox must be suspended during protection",
            "com.flextech.client.terabox" in dpm.rawSuspendedPackages(),
        )
    }

    @Test
    fun `terabox repackaged variant blocked by metadata, not by one hardcoded name`() {
        val device = fakeDevice(systemDialer = "com.device.dialer", inputMethod = "com.device.ime") {
            riy(riyPackage)
            phone()
            keyboard()
            terabox(packageName = "com.clone.terabox.share", label = "Free Videos Cloud")
        }
        val engine = engineFor(device)
        assertEquals(PolicyApplicationResult.APPLIED, engine.applyRestrictedPolicyWithResult(session(), LockTaskPolicy()))
        assertTrue("com.clone.terabox.share" in dpm.rawHiddenPackages())
    }

    // ------------------------------------------------------------- TEST C

    @Test
    fun `testC terabox installed mid-restriction is immediately neutralized`() {
        // Restriction starts WITHOUT TeraBox installed.
        val clean = fakeDevice(systemDialer = "com.device.dialer", inputMethod = "com.device.ime") {
            riy(riyPackage)
            phone()
            keyboard()
            telegram()
        }
        val engine = engineFor(clean)
        assertEquals(PolicyApplicationResult.APPLIED, engine.applyRestrictedPolicyWithResult(session(), LockTaskPolicy()))

        // TeraBox lands mid-restriction (APK sideload). Model the new device
        // contents and run the install-time fast path + reconciliation — the
        // same two calls PackageStateWatcher funnels PACKAGE_ADDED into.
        val infected = fakeDevice(systemDialer = "com.device.dialer", inputMethod = "com.device.ime") {
            riy(riyPackage)
            phone()
            keyboard()
            telegram()
            terabox()
        }
        val engine2 = AndroidEnforcementEngine(
            devicePolicy = dpm,
            discovery = infected,
            appPolicyResolver = PackageManagerAppPolicyResolver(infected, riyPackage),
            riyPackageName = riyPackage,
            clock = { NOW },
        )
        engine2.restoreExpectation(session(), LockTaskPolicy())
        assertTrue(engine2.hardenSinglePackage("com.flextech.client.terabox"))
        assertEquals(ReconciliationResult.VERIFIED, engine2.reconcileWithResult())

        assertTrue("com.flextech.client.terabox" in dpm.rawHiddenPackages())
        assertFalse("com.flextech.client.terabox" in dpm.rawLockTaskPackages())
    }

    // ------------------------------------------------------------- TEST D

    @Test
    fun `testD unauthorized sideloaded app is suspended during protection`() {
        val engine = engineFor(deviceWithTelegramAndTeraBox())
        assertEquals(PolicyApplicationResult.APPLIED, engine.applyRestrictedPolicyWithResult(session(), LockTaskPolicy()))

        assertFalse("com.sideload.sneaky" in dpm.rawLockTaskPackages())
        assertTrue("com.sideload.sneaky" in dpm.rawSuspendedPackages())
    }

    @Test
    fun `unknown-source install restrictions raised during protection`() {
        val engine = engineFor(deviceWithTelegramAndTeraBox())
        engine.applyRestrictedPolicyWithResult(session(), LockTaskPolicy())

        assertTrue(
            DevicePolicyBoundary.RESTRICTION_INSTALL_UNKNOWN_SOURCES in dpm.rawUserRestrictions(),
        )
        assertTrue(
            DevicePolicyBoundary.RESTRICTION_CONFIG_PRIVATE_DNS in dpm.rawUserRestrictions(),
        )
        assertTrue(
            DevicePolicyBoundary.RESTRICTION_CONFIG_VPN in dpm.rawUserRestrictions(),
        )
    }

    @Test
    fun `global install ban is never set so riy self-update keeps working`() {
        val engine = engineFor(deviceWithTelegramAndTeraBox())
        engine.applyRestrictedPolicyWithResult(session(), LockTaskPolicy())

        assertFalse(
            "no_install_apps must never be set (would break RIY self-update)",
            DevicePolicyBoundary.RESTRICTION_INSTALL_APPS in dpm.rawUserRestrictions(),
        )
    }

    // ------------------------------------------------------------- TEST H

    @Test
    fun `testH telegram allowed and terabox blocked survive reboot recovery`() {
        val device = deviceWithTelegramAndTeraBox()
        val engine = engineFor(device)
        engine.applyRestrictedPolicyWithResult(session(), LockTaskPolicy())

        // Simulate process death: fresh engine, same platform state.
        val rebooted = AndroidEnforcementEngine(
            devicePolicy = dpm,
            discovery = device,
            appPolicyResolver = PackageManagerAppPolicyResolver(device, riyPackage),
            riyPackageName = riyPackage,
            clock = { NOW },
        )
        // Recovery entry point rebuilds the expectation WITHOUT a platform
        // write, then reconciliation verifies/corrects with a read-back.
        val restored = rebooted.restoreExpectation(session(), LockTaskPolicy())
        assertTrue("expectation must rebuild after reboot", restored != null)
        assertEquals(ReconciliationResult.VERIFIED, rebooted.reconcileWithResult())

        assertTrue("org.telegram.messenger" in dpm.rawLockTaskPackages())
        assertTrue("com.flextech.client.terabox" in dpm.rawHiddenPackages())
    }

    @Test
    fun `reconcile re-hides terabox when an external actor unhides it`() {
        val device = deviceWithTelegramAndTeraBox()
        val engine = engineFor(device)
        engine.applyRestrictedPolicyWithResult(session(), LockTaskPolicy())

        dpm.simulateExternalUnhide("com.flextech.client.terabox")

        val rebooted = AndroidEnforcementEngine(
            devicePolicy = dpm,
            discovery = device,
            appPolicyResolver = PackageManagerAppPolicyResolver(device, riyPackage),
            riyPackageName = riyPackage,
            clock = { NOW },
        )
        rebooted.restoreExpectation(session(), LockTaskPolicy())
        rebooted.reconcileWithResult()

        assertTrue("com.flextech.client.terabox" in dpm.rawHiddenPackages())
    }

    // ------------------------------------------------------------- TEST I

    @Test
    fun `testI riy itself is never hidden or suspended`() {
        val engine = engineFor(deviceWithTelegramAndTeraBox())
        engine.applyRestrictedPolicyWithResult(session(), LockTaskPolicy())

        assertFalse(riyPackage in dpm.rawHiddenPackages())
        assertFalse(riyPackage in dpm.rawSuspendedPackages())
        assertTrue(riyPackage in dpm.rawLockTaskPackages())
        assertTrue(engine.hardenSinglePackage(riyPackage))
        assertFalse(riyPackage in dpm.rawHiddenPackages())
    }

    @Test
    fun `restore releases hardening without touching ownership`() {
        val engine = engineFor(deviceWithTelegramAndTeraBox())
        engine.applyRestrictedPolicyWithResult(session(), LockTaskPolicy())
        assertEquals(PolicyApplicationResult.APPLIED, engine.restoreNormalPolicyWithResult())

        assertFalse("com.flextech.client.terabox" in dpm.rawHiddenPackages())
        assertFalse("com.sideload.sneaky" in dpm.rawSuspendedPackages())
        assertTrue(dpm.rawUserRestrictions().isEmpty())
        assertTrue(dpm.isDeviceOwnerApp(riyPackage))
    }

    private companion object {
        const val NOW = 1_000_000L
    }
}
