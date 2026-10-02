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

    // ------------------------------------------------- hardening (Device Owner)
    // Best-effort privileged controls used ONLY while a restriction (or
    // protection-wanted hardening) is active. Every method returns whether the
    // platform call itself succeeded; callers must still read back the
    // authoritative state (lock-task allowlist) before claiming enforcement.
    // All are safe no-ops on non-owners (return false, never throw).

    /**
     * Hides ([hidden]=true) or unhides ([hidden]=false) [packageName].
     * Hidden apps disappear from the launcher and cannot be launched.
     * Implements `DevicePolicyManager.setApplicationHidden`.
     */
    fun setApplicationHidden(admin: AdminComponent, packageName: String, hidden: Boolean): Boolean =
        false

    /** True only if [packageName] is currently hidden by policy. */
    fun isApplicationHidden(admin: AdminComponent, packageName: String): Boolean = false

    /**
     * Suspends ([suspended]=true) or unsuspends [packageNames].
     * Suspended apps cannot be launched (system shows a suspended UI).
     * Implements `DevicePolicyManager.setPackagesSuspended`.
     */
    fun setPackagesSuspended(
        admin: AdminComponent,
        packageNames: List<String>,
        suspended: Boolean,
    ): Boolean = false

    /** The subset of [packageNames] currently suspended, read live. */
    fun getSuspendedPackages(admin: AdminComponent, packageNames: List<String>): List<String> =
        emptyList()

    /**
     * Adds the user restriction [restriction] (a `UserManager` key such as
     * `no_install_unknown_sources`, `no_config_private_dns`,
     * `no_config_vpn`). Implements `DevicePolicyManager.addUserRestriction`.
     */
    fun addUserRestriction(admin: AdminComponent, restriction: String): Boolean = false

    /** Removes a previously added user restriction. */
    fun clearUserRestriction(admin: AdminComponent, restriction: String): Boolean = false

    /** True only if [restriction] is currently in force for [admin]. */
    fun hasUserRestriction(admin: AdminComponent, restriction: String): Boolean = false

    companion object {
        /** Official `UserManager` restriction keys, as plain strings (JVM-safe). */
        const val RESTRICTION_INSTALL_UNKNOWN_SOURCES = "no_install_unknown_sources"
        const val RESTRICTION_CONFIG_PRIVATE_DNS = "no_config_private_dns"
        const val RESTRICTION_CONFIG_VPN = "no_config_vpn"
        const val RESTRICTION_INSTALL_APPS = "no_install_apps"
        const val RESTRICTION_UNINSTALL_APPS = "no_uninstall_apps"
    }
}
