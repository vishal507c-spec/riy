package com.vishal.riy.protection.enforcement.platform

import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.os.Build
import android.util.Log

/**
 * The production [DevicePolicyBoundary]: a thin, stateless adapter over a real
 * `android.app.admin.DevicePolicyManager`.
 *
 * This is one of only two classes in the whole app permitted to hold a
 * `DevicePolicyManager` reference (the other being the existing integrity and
 * uninstall-protection components, which are read-only or self-owned). It
 * performs NO policy decisions — it translates calls and reports exactly what
 * the platform holds.
 *
 * Every write returns whether the platform call itself succeeded; the caller
 * must still read the state back before believing enforcement happened.
 */
class AndroidDevicePolicyBoundary(

    context: Context,

) : DevicePolicyBoundary {

    private val dpm: DevicePolicyManager? =
        context.getSystemService(DevicePolicyManager::class.java)

    private fun admin(admin: AdminComponent): ComponentName =
        ComponentName(admin.packageName, admin.className)

    @Suppress("DEPRECATION") // isDeviceOwnerApp is the API 24-compatible check.
    override fun isDeviceOwnerApp(packageName: String): Boolean =
        dpm?.isDeviceOwnerApp(packageName) == true

    override fun isAdminActive(admin: AdminComponent): Boolean =
        dpm?.isAdminActive(this.admin(admin)) == true

    override fun setLockTaskPackages(admin: AdminComponent, packages: List<String>): Boolean {
        val dpm = dpm ?: return false
        return try {
            dpm.setLockTaskPackages(this.admin(admin), packages.toTypedArray())
            true
        } catch (e: SecurityException) {
            // Not device owner, or the admin is not the right component. The
            // engine verifies ownership up front; this guard exists so a
            // surprise revocation can NEVER crash the app.
            Log.e(TAG, "setLockTaskPackages rejected by platform", e)
            false
        }
    }

    override fun getLockTaskPackages(admin: AdminComponent): List<String> {
        // getLockTaskPackages was only added in API 26, while the corresponding
        // setters exist from API 23. Below API 26 the allowlist simply cannot be
        // read back; this boundary reports nothing rather than pretending, so
        // callers correctly treat the state as unverifiable instead of verified.
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            Log.w(TAG, "getLockTaskPackages requires API 26; read-back unavailable on API ${Build.VERSION.SDK_INT}")
            return emptyList()
        }
        return try {
            dpm?.getLockTaskPackages(this.admin(admin))?.toList() ?: emptyList()
        } catch (e: SecurityException) {
            Log.e(TAG, "getLockTaskPackages rejected by platform", e)
            emptyList()
        }
    }

    // setLockTaskFeatures/getLockTaskFeatures exist only from API 28 (P).
    override val lockTaskFeaturesSupported: Boolean =
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.P

    override fun setLockTaskFeatures(admin: AdminComponent, features: LockTaskFeatures): Boolean {
        val dpm = dpm ?: return false
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) {
            Log.w(TAG, "setLockTaskFeatures requires API 28; features left at platform default on API ${Build.VERSION.SDK_INT}")
            return false
        }
        return try {
            // platformConforming() guarantees the hierarchy Android requires
            // (system info -> notifications -> home), so the platform call can
            // never be rejected with IllegalArgumentException for an invalid
            // combination. It widens the set minimally, never narrows it.
            dpm.setLockTaskFeatures(this.admin(admin), features.platformConforming().toPlatformFlags())
            true
        } catch (e: SecurityException) {
            Log.e(TAG, "setLockTaskFeatures rejected by platform", e)
            false
        }
    }

    override fun getLockTaskFeatures(admin: AdminComponent): LockTaskFeatures {
        // Pre-API 28 there IS no feature control: lock task simply leaves every
        // affordance on, so reporting UNRESTRICTED is the truthful answer.
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) {
            Log.w(TAG, "getLockTaskFeatures requires API 28; reporting platform default on API ${Build.VERSION.SDK_INT}")
            return LockTaskFeatures.UNRESTRICTED
        }
        val flags = try {
            dpm?.getLockTaskFeatures(this.admin(admin)) ?: 0
        } catch (e: SecurityException) {
            Log.e(TAG, "getLockTaskFeatures rejected by platform", e)
            0
        }
        return flags.toLockTaskFeatures()
    }

    override fun isLockTaskPermitted(packageName: String): Boolean =
        try {
            dpm?.isLockTaskPermitted(packageName) == true
        } catch (e: SecurityException) {
            Log.e(TAG, "isLockTaskPermitted rejected by platform", e)
            false
        }

    override fun isUninstallBlocked(admin: AdminComponent, packageName: String): Boolean =
        try {
            dpm?.isUninstallBlocked(this.admin(admin), packageName) == true
        } catch (e: SecurityException) {
            Log.e(TAG, "isUninstallBlocked rejected by platform", e)
            false
        }

    private companion object {
        const val TAG = "RiyDpmBoundary"
    }
}

// ---------------------------------------------------------------------------
// Flag translation. Lives here, and ONLY here, so that the pure
// [LockTaskFeatures] type above never has to name an Android constant.
// ---------------------------------------------------------------------------

/**
 * ORs a [LockTaskFeatures] into the real `DevicePolicyManager` flag set.
 * Conservative: every feature is opted in individually, so nothing implicit
 * (such as the keyguard flag, which is deliberately never set) can sneak in.
 *
 * Every constant and call below is API 23+; minSdk is 24, so they are always
 * available and no per-feature version guard is needed.
 */
private fun LockTaskFeatures.toPlatformFlags(): Int {
    var flags = DevicePolicyManager.LOCK_TASK_FEATURE_NONE
    if (allowSystemInfo) flags = flags or DevicePolicyManager.LOCK_TASK_FEATURE_SYSTEM_INFO
    if (allowNotifications) flags = flags or DevicePolicyManager.LOCK_TASK_FEATURE_NOTIFICATIONS
    if (allowHome) flags = flags or DevicePolicyManager.LOCK_TASK_FEATURE_HOME
    if (allowOverview) flags = flags or DevicePolicyManager.LOCK_TASK_FEATURE_OVERVIEW
    if (allowGlobalActions) flags = flags or DevicePolicyManager.LOCK_TASK_FEATURE_GLOBAL_ACTIONS
    return flags
}

/** Reverses [toPlatformFlags] by testing each known bit against [flags]. */
private fun Int.toLockTaskFeatures(): LockTaskFeatures = LockTaskFeatures(
    allowHome = this and DevicePolicyManager.LOCK_TASK_FEATURE_HOME != 0,
    allowOverview = this and DevicePolicyManager.LOCK_TASK_FEATURE_OVERVIEW != 0,
    allowNotifications = this and DevicePolicyManager.LOCK_TASK_FEATURE_NOTIFICATIONS != 0,
    allowSystemInfo = this and DevicePolicyManager.LOCK_TASK_FEATURE_SYSTEM_INFO != 0,
    allowGlobalActions = this and DevicePolicyManager.LOCK_TASK_FEATURE_GLOBAL_ACTIONS != 0,
)
