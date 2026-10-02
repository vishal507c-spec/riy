package com.vishal.riy.protection.enforcement

import com.vishal.riy.protection.enforcement.platform.AdminComponent
import com.vishal.riy.protection.enforcement.platform.DevicePolicyBoundary
import com.vishal.riy.protection.enforcement.platform.LockTaskFeatures

/**
 * JVM test double for [DevicePolicyBoundary]. It models a Device Owner's
 * lock-task state in plain memory so the full behaviour of
 * [AndroidEnforcementEngine] can be proven WITHOUT a real
 * `DevicePolicyManager` and WITHOUT a device.
 *
 * The state it holds is what the real platform WOULD hold, and the failure
 * injection switches let a test simulate a revoked owner or a rejected write.
 */
class FakeDevicePolicyBoundary(

    /** Set this to the package that is provisioned as Device Owner. */
    var deviceOwnerPackage: String? = null,

    /** Whether the admin component is an active device admin. */
    var adminActive: Boolean = true,

    /** Whether RIY's package is uninstall-blocked (read-only, like the real one). */
    var uninstallBlocked: Boolean = true,

) : DevicePolicyBoundary {

    /** Models a platform that can/cannot control lock-task features (API 28+). */
    override var lockTaskFeaturesSupported: Boolean = true

    // ---- failure injection -------------------------------------------------

    /** Simulates the platform rejecting setLockTaskPackages (e.g. owner revoked). */
    var rejectSetLockTaskPackages: Boolean = false

    /** Simulates the platform rejecting setLockTaskFeatures. */
    var rejectSetLockTaskFeatures: Boolean = false

    /** Simulates the platform lying on read-back (used to prove verify-on-read). */
    var corruptLockTaskPackagesOnRead: Boolean = false

    /** Modelled hidden packages (setApplicationHidden state). */
    private val hiddenPackages: MutableSet<String> = mutableSetOf()

    /** Modelled suspended packages (setPackagesSuspended state). */
    private val suspendedPackages: MutableSet<String> = mutableSetOf()

    /** Modelled user restrictions in force. */
    private val userRestrictions: MutableSet<String> = mutableSetOf()

    /** Simulates the platform rejecting hardening writes. */
    var rejectHardeningWrites: Boolean = false

    // ---- modelled platform state ------------------------------------------

    private var lockTaskPackages: List<String> = emptyList()

    private var lockTaskFeatures: LockTaskFeatures = LockTaskFeatures.UNRESTRICTED

    /** How many times setLockTaskPackages actually changed the state. */
    var setLockTaskPackagesCallCount: Int = 0
        private set

    override fun isDeviceOwnerApp(packageName: String): Boolean = deviceOwnerPackage == packageName

    override fun isAdminActive(admin: AdminComponent): Boolean = adminActive

    override fun setLockTaskPackages(admin: AdminComponent, packages: List<String>): Boolean {
        // The real platform throws SecurityException for a non-owner; the fake
        // models that as a rejected write so the engine's owner gate is tested.
        if (!isDeviceOwnerApp(admin.packageName)) return false
        if (rejectSetLockTaskPackages) return false
        setLockTaskPackagesCallCount++
        lockTaskPackages = packages.toList()
        return true
    }

    override fun getLockTaskPackages(admin: AdminComponent): List<String> =
        if (corruptLockTaskPackagesOnRead) lockTaskPackages + CORRUPTED_PACKAGE
        else lockTaskPackages.toList()

    override fun setLockTaskFeatures(admin: AdminComponent, features: LockTaskFeatures): Boolean {
        if (!isDeviceOwnerApp(admin.packageName)) return false
        if (!lockTaskFeaturesSupported) return false
        if (rejectSetLockTaskFeatures) return false
        lockTaskFeatures = features
        return true
    }

    override fun getLockTaskFeatures(admin: AdminComponent): LockTaskFeatures =
        if (lockTaskFeaturesSupported) lockTaskFeatures else LockTaskFeatures.UNRESTRICTED

    override fun isLockTaskPermitted(packageName: String): Boolean = packageName in lockTaskPackages

    override fun isUninstallBlocked(admin: AdminComponent, packageName: String): Boolean =
        uninstallBlocked

    /** Direct read of the modelled allowlist, without the corruption switch. */
    fun rawLockTaskPackages(): List<String> = lockTaskPackages.toList()

    /** Direct read of the modelled feature set. */
    fun recordedLockTaskFeatures(): LockTaskFeatures = lockTaskFeatures

    /** Builds an [AdminComponent] for a test's owner package. */
    fun adminFixture(packageName: String): AdminComponent =
        AdminComponent(packageName, ADMIN_CLASS)

    /** Simulates an external actor (another DPM caller) changing the allowlist. */
    fun simulateExternalDrift(packages: List<String>) {
        lockTaskPackages = packages.toList()
    }

    override fun setApplicationHidden(
        admin: AdminComponent,
        packageName: String,
        hidden: Boolean,
    ): Boolean {
        if (!isDeviceOwnerApp(admin.packageName)) return false
        if (rejectHardeningWrites) return false
        if (hidden) hiddenPackages += packageName else hiddenPackages -= packageName
        return true
    }

    override fun isApplicationHidden(admin: AdminComponent, packageName: String): Boolean =
        packageName in hiddenPackages

    override fun setPackagesSuspended(
        admin: AdminComponent,
        packageNames: List<String>,
        suspended: Boolean,
    ): Boolean {
        if (!isDeviceOwnerApp(admin.packageName)) return false
        if (rejectHardeningWrites) return false
        if (suspended) suspendedPackages += packageNames else suspendedPackages -= packageNames.toSet()
        return true
    }

    override fun getSuspendedPackages(
        admin: AdminComponent,
        packageNames: List<String>,
    ): List<String> = packageNames.filter { it in suspendedPackages }

    override fun addUserRestriction(admin: AdminComponent, restriction: String): Boolean {
        if (!isDeviceOwnerApp(admin.packageName)) return false
        if (rejectHardeningWrites) return false
        userRestrictions += restriction
        return true
    }

    override fun clearUserRestriction(admin: AdminComponent, restriction: String): Boolean {
        if (!isDeviceOwnerApp(admin.packageName)) return false
        if (rejectHardeningWrites) return false
        userRestrictions -= restriction
        return true
    }

    override fun hasUserRestriction(admin: AdminComponent, restriction: String): Boolean =
        restriction in userRestrictions

    /** Direct read of modelled hidden packages (tests only). */
    fun rawHiddenPackages(): Set<String> = hiddenPackages.toSet()

    /** Direct read of modelled suspended packages (tests only). */
    fun rawSuspendedPackages(): Set<String> = suspendedPackages.toSet()

    /** Direct read of modelled user restrictions (tests only). */
    fun rawUserRestrictions(): Set<String> = userRestrictions.toSet()

    /** Simulates an external actor unhiding/un-suspending a package. */
    fun simulateExternalUnhide(packageName: String) {
        hiddenPackages -= packageName
        suspendedPackages -= packageName
    }

    private companion object {
        const val CORRUPTED_PACKAGE = "corrupted.read.back.package"
        const val ADMIN_CLASS = "com.vishal.riy.admin.RiyDeviceAdminReceiver"
    }
}
