package com.vishal.riy.protection.enforcement.platform

import com.vishal.riy.protection.enforcement.LockTaskPolicy

/**
 * The lock-task feature configuration expressed as pure Kotlin booleans —
 * deliberately NOT as `DevicePolicyManager.LOCK_TASK_FEATURE_*` bit flags, so
 * nothing above this seam has to touch an Android constant.
 *
 * [AndroidDevicePolicyBoundary.toPlatformFlags] is the single place that ORs
 * these into the real `DevicePolicyManager` flag set.
 *
 * Semantics match the platform's own description: a flag turned OFF means the
 * corresponding affordance is NOT available to the user while a lock-task
 * session is active.
 *
 * @param allowHome          whether the Home action/gesture is available.
 * @param allowOverview      whether Overview (recents) is available.
 * @param allowNotifications whether notifications are shown.
 * @param allowSystemInfo    whether system info (status bar icons) is shown.
 * @param allowGlobalActions whether global actions (power menu, emergency
 *                           affordances) are available. Kept ON deliberately —
 *                           emergency calling must never be disabled.
 */
data class LockTaskFeatures(

    val allowHome: Boolean,

    val allowOverview: Boolean,

    val allowNotifications: Boolean,

    val allowSystemInfo: Boolean,

    val allowGlobalActions: Boolean,
) {

    companion object {

        /**
         * Restrictive-but-usable: home and overview are blocked so the user
         * cannot escape into an arbitrary launcher/recents, while
         * notifications, system info and global actions (power/emergency)
         * stay available so the device remains genuinely usable.
         */
        val RESTRICTED: LockTaskFeatures = LockTaskFeatures(
            allowHome = false,
            allowOverview = false,
            allowNotifications = true,
            allowSystemInfo = true,
            allowGlobalActions = true,
        )

        /** Everything on; used while protection is NORMAL. */
        val UNRESTRICTED: LockTaskFeatures = LockTaskFeatures(
            allowHome = true,
            allowOverview = true,
            allowNotifications = true,
            allowSystemInfo = true,
            allowGlobalActions = true,
        )

        /** Derives the feature set from a [LockTaskPolicy]. */
        fun from(policy: LockTaskPolicy): LockTaskFeatures = LockTaskFeatures(
            allowHome = policy.allowHome,
            allowOverview = policy.allowOverview,
            allowNotifications = policy.allowNotifications,
            allowSystemInfo = policy.allowSystemInfo,
            allowGlobalActions = policy.allowGlobalActions,
        )
    }

    /**
     * The smallest SUPERSET of this feature set that Android will actually
     * accept.
     *
     * PLATFORM INVARIANT — `DevicePolicyManager.setLockTaskFeatures` rejects
     * (with `IllegalArgumentException`) any combination that violates the
     * hierarchy the platform enforces internally:
     *
     *     LOCK_TASK_FEATURE_SYSTEM_INFO  requires  LOCK_TASK_FEATURE_NOTIFICATIONS
     *     LOCK_TASK_FEATURE_NOTIFICATIONS requires  LOCK_TASK_FEATURE_HOME
     *
     * So a policy may not keep notifications/status-bar info while disabling
     * Home; Android simply refuses the call.
     *
     * This function resolves that conflict in ONE place, centrally, at the
     * platform boundary — callers never have to know the rule. It turns ON
     * only the affordances the platform forces, and it never turns anything
     * OFF, so a restrictive policy stays as restrictive as Android allows:
     * Overview (recents) is still blocked and the keyguard flag is never set.
     * Choosing Home over "no notifications" keeps the device genuinely usable,
     * which is the Phase 4 design intent.
     */
    fun platformConforming(): LockTaskFeatures {
        // Resolve the dependency chain bottom-up: system info pulls in
        // notifications, and notifications pulls in home.
        val notifications = allowNotifications || allowSystemInfo
        val home = allowHome || notifications
        return copy(
            allowHome = home,
            allowNotifications = notifications,
        )
    }
}
