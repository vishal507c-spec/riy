package com.vishal.riy.protection.enforcement

import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import androidx.test.platform.app.InstrumentationRegistry
import com.vishal.riy.admin.RiyDeviceAdminReceiver
import com.vishal.riy.protection.enforcement.platform.AndroidDevicePolicyBoundary
import com.vishal.riy.protection.enforcement.platform.AndroidPackageDiscoveryBoundary
import com.vishal.riy.protection.enforcement.platform.DevicePolicyBoundary
import com.vishal.riy.protection.enforcement.platform.PackageDiscoveryBoundary
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * On-device verification of the REAL enforcement layer. These run against the
 * actual DevicePolicyManager and PackageManager of the target device — the
 * Infinix X663C — and read back live platform state rather than any model.
 *
 * Read-only discovery (present/absent, package resolution) runs on any device.
 * The privileged apply/restore tests require RIY to be provisioned as Device
 * Owner (`dpm set-device-owner`); when it is not, they skip rather than fail,
 * because the engine itself would refuse to act.
 */
class AndroidEnforcementEngineInstrumentationTest {

    private val context: Context = InstrumentationRegistry.getInstrumentation().targetContext

    private val dpm: DevicePolicyManager =
        context.getSystemService(DevicePolicyManager::class.java)

    private val admin: ComponentName = ComponentName(context, RiyDeviceAdminReceiver::class.java)

    private lateinit var devicePolicy: DevicePolicyBoundary
    private lateinit var discovery: PackageDiscoveryBoundary
    private lateinit var resolver: AppPolicyResolver
    private lateinit var engine: AndroidEnforcementEngine

    @Before
    fun setUp() {
        devicePolicy = AndroidDevicePolicyBoundary(context)
        discovery = AndroidPackageDiscoveryBoundary(context)
        resolver = PackageManagerAppPolicyResolver(discovery, context.packageName)
        engine = AndroidEnforcementEngine(
            devicePolicy = devicePolicy,
            discovery = discovery,
            appPolicyResolver = resolver,
            riyPackageName = context.packageName,
        )
    }

    private fun isDeviceOwner(): Boolean {
        @Suppress("DEPRECATION") // isDeviceOwnerApp is the API 24-compatible check.
        return dpm.isDeviceOwnerApp(context.packageName)
    }

    // ---------------------------------------------------- READ-ONLY (any device)

    @Test
    fun riyPackageIsPresentAndEnabled() {
        val report = engine.diagnostics()
        assertTrue("RIY must be installed", report.riyPackagePresent)
        assertTrue("RIY must be enabled", report.riyEnabled)
    }

    @Test
    fun adminComponentIsTheDocumentedReceiver() {
        val report = engine.diagnostics()
        assertEquals(
            "the authoritative admin is RiyDeviceAdminReceiver",
            "${context.packageName}/com.vishal.riy.admin.RiyDeviceAdminReceiver",
            report.adminComponent,
        )
    }

    @Test
    fun diagnosticsReflectsActualDeviceState() {
        val report = engine.diagnostics()

        // Whatever the device reports, the diagnostic fields must agree with a
        // direct, independent read of the framework.
        assertEquals(isDeviceOwner(), report.deviceOwner)
        assertNotNull(report.generatedAt)
        assertFalse("report must be printable", report.toReportString().isEmpty())
    }

    // ------------------------------------------------ PRIVILEGED (device owner only)

    @Test
    fun deviceOwnerAppliesAndRestoresRestrictedPolicy() {
        org.junit.Assume.assumeTrue("RIY is not Device Owner — skipping privileged test", isDeviceOwner())

        val session = com.vishal.riy.protection.state.ProtectionSession(
            sessionId = "instrumentation-test",
            state = com.vishal.riy.protection.policy.ProtectionState.RESTRICTED,
            startTime = System.currentTimeMillis(),
            expiryTime = System.currentTimeMillis() + 7_200_000L,
            reason = "instrumentation_test",
            policyVersion = 1,
        )

        try {
            // 1. Apply the restrictive policy.
            val applied = engine.applyRestrictedPolicyWithResult(session, LockTaskPolicy())
            assertEquals(PolicyApplicationResult.APPLIED, applied)

            // 2. Read the ACTUAL platform allowlist back.
            val actual = devicePolicy.getLockTaskPackages(
                com.vishal.riy.protection.enforcement.platform.AdminComponent(
                    context.packageName,
                    RiyDeviceAdminReceiver::class.java.name,
                ),
            )
            assertTrue("RIY must remain allowed", actual.contains(context.packageName))
            assertTrue(
                "a phone package must be allowed",
                resolver.resolveAllowedApps().any { it.category == AllowedAppCategory.PHONE },
            )

            // 3. Reconcile must confirm from the read-back, not the request.
            assertEquals(ReconciliationResult.VERIFIED, engine.reconcileWithResult())

            // 4. Status must reflect the applied restriction.
            assertEquals(EnforcementStatus.ACTIVE_RESTRICTED, engine.currentStatus())
        } finally {
            // 5. ALWAYS restore normal policy, even on assertion failure.
            val restored = engine.restoreNormalPolicyWithResult()
            assertEquals(PolicyApplicationResult.APPLIED, restored)
        }

        // 6. After restore: normal state, and ownership/protection intact.
        assertEquals(EnforcementStatus.NORMAL, engine.currentStatus())
        assertTrue("Device Owner must remain active", isDeviceOwner())
        assertTrue(
            "uninstall protection must remain active",
            dpm.isUninstallBlocked(admin, context.packageName),
        )
    }

    @Test
    fun nonDeviceOwnerDoesNotAttemptPrivilegedOperations() {
        org.junit.Assume.assumeFalse("This device IS the owner — covered by the other test", isDeviceOwner())

        val session = com.vishal.riy.protection.state.ProtectionSession(
            sessionId = "instrumentation-test-nonowner",
            state = com.vishal.riy.protection.policy.ProtectionState.RESTRICTED,
            startTime = System.currentTimeMillis(),
            expiryTime = System.currentTimeMillis() + 7_200_000L,
            reason = "instrumentation_test",
            policyVersion = 1,
        )

        val result = engine.applyRestrictedPolicyWithResult(session, LockTaskPolicy())
        assertEquals(
            "a non-owner device must get an explicit non-owner result",
            PolicyApplicationResult.NOT_DEVICE_OWNER,
            result,
        )
    }
}
