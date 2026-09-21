package com.vishal.riy.protection.enforcement.platform

/**
 * THE seam in front of `android.app.admin.DevicePolicyManager`.
 *
 * Everything above this interface — the policy engine, the restricted-mode
 * controller and the enforcement engine itself — speaks only pure Kotlin.
 * Only [AndroidDevicePolicyBoundary] (and a JVM test fake) implements it, and
 * only that implementation may hold a real `DevicePolicyManager`.
 *
 * Reporting contract: every read method returns what the platform ACTUALLY
 * holds, never a cached or requested value, so reconciliation can compare
 * "what we asked for" against "what Android really did".
 */
interface DevicePolicyBoundary {

    /**
     * True only when this platform can control lock-task FEATURES
     * (`setLockTaskFeatures` / `getLockTaskFeatures` exist from API 28). Below
     * that, the allowlist still applies but Home/Overview cannot be blocked,
     * so callers must neither claim nor verify feature control.
     */
    val lockTaskFeaturesSupported: Boolean

    /**
     * True only if [packageName] is provisioned as the Device Owner.
     * Implements `DevicePolicyManager.isDeviceOwnerApp`.
     */
    fun isDeviceOwnerApp(packageName: String): Boolean

    /** True only if the [admin] component is an active device admin. */
    fun isAdminActive(admin: AdminComponent): Boolean

    /**
     * Applies [packages] as the lock-task allowlist for [admin].
     *
     * @return true only if the platform call itself succeeded (the caller must
     *   STILL read back via [getLockTaskPackages] before claiming enforcement).
     */
    fun setLockTaskPackages(admin: AdminComponent, packages: List<String>): Boolean

    /**
     * Reads the CURRENT lock-task allowlist straight from the platform.
     * This is the ground truth used for reconciliation.
     */
    fun getLockTaskPackages(admin: AdminComponent): List<String>

    /**
     * Applies [features] as the lock-task feature set for [admin].
     *
     * @return true only if the platform call itself succeeded.
     */
    fun setLockTaskFeatures(admin: AdminComponent, features: LockTaskFeatures): Boolean

    /** Reads the CURRENT lock-task feature set from the platform. */
    fun getLockTaskFeatures(admin: AdminComponent): LockTaskFeatures

    /** True only if [packageName] is permitted to run under lock task. */
    fun isLockTaskPermitted(packageName: String): Boolean

    /**
     * True only if [packageName] is currently uninstall-blocked by policy.
     * READ-ONLY here: this boundary never alters uninstall protection, which
     * is owned exclusively by `com.vishal.riy.admin.UninstallProtection`.
     */
    fun isUninstallBlocked(admin: AdminComponent, packageName: String): Boolean
}
