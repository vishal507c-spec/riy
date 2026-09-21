package com.vishal.riy.protection.integrity

import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.content.pm.PackageManager.NameNotFoundException
import android.app.admin.DevicePolicyManager
import com.vishal.riy.admin.RiyDeviceAdminReceiver

/**
 * Read-oriented package integrity verification. It NEVER modifies state and it
 * NEVER duplicates the authority of [com.vishal.riy.admin.UninstallProtection]:
 * that component owns uninstall blocking, this one only *checks* it.
 *
 * Every field it reports is read live from the framework, so the UI can never
 * display a protection status that is not actually true.
 */
class PackageIntegrityEngine(private val context: Context) {

    /** Complete picture of RIY's package-level presence on this device. */
    data class PackageState(
        val installed: Boolean,
        val enabled: Boolean,
        val launcherActivityResolves: Boolean,
        val deviceOwner: Boolean,
        val adminComponentEnabled: Boolean,
        val uninstallBlocked: Boolean,
        val issues: List<IntegrityIssue>,
    ) {

        /** Overall verdict: true only when every individual check passes. */
        val verified: Boolean
            get() = installed && enabled && launcherActivityResolves && deviceOwner &&
                adminComponentEnabled && uninstallBlocked &&
                issues.none { it.severity == IntegrityIssue.Severity.ERROR }
    }

    /** The single authoritative snapshot of RIY's package state right now. */
    fun verify(): PackageState {
        val issues = mutableListOf<IntegrityIssue>()

        val pm = context.packageManager
        val info: PackageInfo? = try {
            pm.getPackageInfo(context.packageName, 0)
        } catch (_: NameNotFoundException) {
            null
        }

        val installed = info != null
        if (!installed) {
            issues += IntegrityIssue(
                component = COMPONENT_PACKAGE,
                description = "RIY package is not installed",
                severity = IntegrityIssue.Severity.ERROR,
            )
        }

        val enabled = installed && info!!.applicationInfo?.enabled == true
        if (installed && !enabled) {
            issues += IntegrityIssue(
                component = COMPONENT_PACKAGE,
                description = "RIY package is disabled",
                severity = IntegrityIssue.Severity.ERROR,
            )
        }

        val launcherResolves = installed && pm.getLaunchIntentForPackage(context.packageName) != null
        if (installed && !launcherResolves) {
            issues += IntegrityIssue(
                component = COMPONENT_LAUNCHER,
                description = "Launcher activity does not resolve",
                severity = IntegrityIssue.Severity.ERROR,
            )
        }

        // Device Owner + admin component.
        val dpm = context.getSystemService(DevicePolicyManager::class.java)
        val admin = ComponentName(context, RiyDeviceAdminReceiver::class.java)
        @Suppress("DEPRECATION") // isDeviceOwnerApp is the API 24-compatible check.
        val isOwner = dpm?.isDeviceOwnerApp(context.packageName) == true
        if (!isOwner) {
            issues += IntegrityIssue(
                component = COMPONENT_DEVICE_OWNER,
                description = "RIY is not the Device Owner",
                severity = IntegrityIssue.Severity.ERROR,
            )
        }

        val isAdminEnabled = installed && dpm?.isAdminActive(admin) == true
        if (isOwner && !isAdminEnabled) {
            issues += IntegrityIssue(
                component = COMPONENT_ADMIN,
                description = "Admin component is not active",
                severity = IntegrityIssue.Severity.ERROR,
            )
        }

        // Uninstall protection is VERIFIED, never modified here.
        val uninstallBlocked = installed && dpm?.isUninstallBlocked(admin, context.packageName) == true
        if (isOwner && !uninstallBlocked) {
            issues += IntegrityIssue(
                component = COMPONENT_UNINSTALL,
                description = "Uninstall protection is not active",
                severity = IntegrityIssue.Severity.WARN,
            )
        }

        return PackageState(
            installed = installed,
            enabled = enabled,
            launcherActivityResolves = launcherResolves,
            deviceOwner = isOwner,
            adminComponentEnabled = isAdminEnabled,
            uninstallBlocked = uninstallBlocked,
            issues = issues,
        )
    }

    companion object {
        const val COMPONENT_PACKAGE = "package"
        const val COMPONENT_LAUNCHER = "launcher"
        const val COMPONENT_DEVICE_OWNER = "device_owner"
        const val COMPONENT_ADMIN = "admin_component"
        const val COMPONENT_UNINSTALL = "uninstall_protection"
        const val COMPONENT_FREEZER = "freezer"
    }
}
