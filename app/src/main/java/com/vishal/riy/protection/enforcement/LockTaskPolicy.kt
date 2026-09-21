package com.vishal.riy.protection.enforcement

/**
 * Immutable description of the lock-task configuration the enforcement engine
 * must apply. Pure Kotlin data; nothing here touches Android yet.
 *
 * Defaults describe a restrictive-but-usable session: home, overview and
 * recent-apps are blocked, while notifications, system info and global actions
 * (including power/emergency affordances Android exposes on the lock task bar)
 * remain available — the device stays usable for a real emergency.
 *
 * [allowedPackages] defaults empty because the authoritative allowlist is
 * resolved from the live device at enforcement time by
 * [com.vishal.riy.protection.enforcement.AppPolicyResolver]; a caller who
 * already knows a package it wants may list it explicitly and it is merged in.
 *
 * @param allowedPackages  packages permitted to run under lock task.
 * @param allowHome        whether the Home gesture/action is available.
 * @param allowOverview    whether Overview (recents) is available.
 * @param allowNotifications whether notifications are shown.
 * @param allowSystemInfo  whether system info (status bar icons) is shown.
 * @param allowGlobalActions whether global actions (power menu etc.) are available.
 */
data class LockTaskPolicy(

    val allowedPackages: List<String> = emptyList(),

    val allowHome: Boolean = false,

    val allowOverview: Boolean = false,

    val allowNotifications: Boolean = true,

    val allowSystemInfo: Boolean = true,

    val allowGlobalActions: Boolean = true,
) {

    companion object {

        /**
         * A permissive baseline used while protection is NORMAL. Nothing is
         * restricted; the allowlist is effectively empty and every affordance
         * stays on.
         */
        val UNRESTRICTED: LockTaskPolicy = LockTaskPolicy(
            allowedPackages = emptyList(),
            allowHome = true,
            allowOverview = true,
            allowNotifications = true,
            allowSystemInfo = true,
            allowGlobalActions = true,
        )
    }
}
