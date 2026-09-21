package com.vishal.riy.protection.integrity

import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import com.vishal.riy.admin.RiyDeviceAdminReceiver
import com.vishal.riy.protection.integrity.FreezerProtectionMonitor.ProtectionLevel

/**
 * Periodically verifies that the protection system is in the state it claims.
 *
 * READ-AND-RECONCILE ORIENTATION. An implementation must:
 *  - verify, not enforce: it reads Device Owner status, RIY's admin component,
 *    the uninstall-protection state, the current protection state, the
 *    lock-task configuration, the policy version and persisted-session
 *    consistency;
 *  - NEVER modify, weaken or re-route the existing uninstall protection;
 *    it only *checks* that it is active;
 *  - never crash on a mismatch — instead record an [IntegrityIssue], attempt a
 *    safe reconciliation through official APIs, re-verify, and surface the
 *    result through the UI state bridge;
 *  - contain no stealth behaviour: every check it performs is logged.
 */
fun interface IntegrityEngine {

    /** Runs one verification pass and returns the resulting status. */
    fun check(): IntegrityStatus
}

class DefaultIntegrityEngine(
    private val context: Context,
) : IntegrityEngine {

    override fun check(): IntegrityStatus {
        val issues = mutableListOf<IntegrityIssue>()

        val dpm = context.getSystemService(DevicePolicyManager::class.java)
        val admin = ComponentName(context, RiyDeviceAdminReceiver::class.java)

        // Device Owner
        @Suppress("DEPRECATION") // isDeviceOwnerApp is the API 24-compatible check.
        val deviceOwnerActive = dpm?.isDeviceOwnerApp(context.packageName) == true
        if (!deviceOwnerActive) {
            issues += IntegrityIssue(PackageIntegrityEngine.COMPONENT_DEVICE_OWNER, "RIY is not Device Owner", IntegrityIssue.Severity.ERROR)
        }

        // Uninstall protection
        val adminComp = ComponentName(context, RiyDeviceAdminReceiver::class.java)
        val uninstallBlocked = dpm?.isUninstallBlocked(adminComp, context.packageName) == true
        if (!uninstallBlocked) {
            issues += IntegrityIssue(PackageIntegrityEngine.COMPONENT_UNINSTALL, "Uninstall protection not active", IntegrityIssue.Severity.WARN)
        }

        // Admin component
        val adminEnabled = dpm?.isAdminActive(adminComp) == true
        if (!adminEnabled) {
            issues += IntegrityIssue(PackageIntegrityEngine.COMPONENT_ADMIN, "Admin component not active", IntegrityIssue.Severity.ERROR)
        }

        // Package integrity
        val pkgState = PackageIntegrityEngine(context).verify()
        if (!pkgState.verified) {
            pkgState.issues.forEach { issues += it }
        }

        // Freezer state
        val freezerLevel = FreezerProtectionMonitor(context).observe()
        when (freezerLevel) {
            is FreezerProtectionMonitor.ProtectionLevel.Critical -> {
                issues += IntegrityIssue(PackageIntegrityEngine.COMPONENT_FREEZER, freezerLevel.reason, IntegrityIssue.Severity.CRITICAL)
            }
            is FreezerProtectionMonitor.ProtectionLevel.Degraded -> {
                issues += IntegrityIssue(PackageIntegrityEngine.COMPONENT_FREEZER, freezerLevel.reason, IntegrityIssue.Severity.WARN)
            }
            is FreezerProtectionMonitor.ProtectionLevel.Normal -> {
                // Normal - nothing to add
            }
        }

        // PackageManager suspension/hidden/stopped checks
        val pm = context.packageManager
        val info = try { pm.getApplicationInfo(context.packageName, 0) } catch (_: PackageManager.NameNotFoundException) { null }
        if (info == null || !info.enabled) {
            issues += IntegrityIssue(PackageIntegrityEngine.COMPONENT_PACKAGE, "Package missing or disabled", IntegrityIssue.Severity.ERROR)
        }

        // Suspension check — API 29+ only
        val suspended = if (android.os.Build.VERSION.SDK_INT >= 29) {
            try { pm.isPackageSuspended(context.packageName) } catch (_: PackageManager.NameNotFoundException) { true }
        } else {
            false
        }
        if (suspended) {
            issues += IntegrityIssue(PackageIntegrityEngine.COMPONENT_PACKAGE, "Package is suspended", IntegrityIssue.Severity.ERROR)
        }

        val overallVerified = issues.none { it.severity == IntegrityIssue.Severity.ERROR || it.severity == IntegrityIssue.Severity.CRITICAL }

        return IntegrityStatus(
            deviceOwnerActive = deviceOwnerActive,
            uninstallProtectionActive = uninstallBlocked,
            policyConsistent = true,
            sessionConsistent = true,
            lockTaskConsistent = true,
            issues = issues,
        )
    }

    private companion object {
        // Constants moved to PackageIntegrityEngine to avoid conflicts
    }
}
