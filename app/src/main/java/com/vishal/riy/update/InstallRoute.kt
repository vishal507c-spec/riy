package com.vishal.riy.update

import com.vishal.riy.R

/**
 * HOW an update may legitimately be installed on this device, decided from
 * facts the platform actually reports.
 */
enum class UpdateChannel {
    /** The normal Android package installer (a real `PackageInstaller` session). */
    SYSTEM_INSTALLER,

    /** A development PC over ADB. The app never installs anything here itself. */
    USB_ADB,
}

/**
 * Why direct installation is not available. Only ever a reason to explain
 * something to the user — never a reason to work around anything.
 */
enum class InstallBlocker {
    NONE,

    /** The user simply has not granted "install unknown apps" yet. */
    NOT_GRANTED,

    /** An administrator (policy) owns the setting; it cannot be granted at all. */
    POLICY_MANAGED,
}

/**
 * Everything [InstallRouteDecider] is allowed to know, gathered by
 * `InstallEnvironmentProbe` from the real platform. Pure Kotlin, so the whole
 * routing policy is unit-testable.
 *
 * @param sdkInt                        device API level.
 * @param canRequestInstalls            the per-app "install unknown apps" grant.
 * @param unknownSourcesPolicyManaged   the system unknown-sources restriction is
 *                                      active, so a non-owner app cannot use the
 *                                      switch. When this app is the owner, its
 *                                      own exemption is attempted before routing.
 * @param isDeviceOrProfileOwner        we are legitimately Device/Profile Owner.
 * @param ownRestrictionLifted          we cleared OUR OWN unknown-sources
 *                                      restriction through official policy APIs.
 * @param alreadyPromptedForGrant       the user was already sent to that settings
 *                                      screen once and did not come back granted.
 * @param installBlockedObserved        the platform already refused an install
 *                                      with STATUS_FAILURE_BLOCKED.
 */
data class InstallEnvironment(
    val sdkInt: Int = 0,
    val canRequestInstalls: Boolean = false,
    val unknownSourcesPolicyManaged: Boolean = false,
    val isDeviceOrProfileOwner: Boolean = false,
    val ownRestrictionLifted: Boolean = false,
    val alreadyPromptedForGrant: Boolean = false,

    /**
     * The platform has ALREADY answered `STATUS_FAILURE_BLOCKED` for an install we
     * attempted — the authoritative, public signal that an administrator owns this
     * setting ("Blocked by your IT admin").
     *
     * Android cannot identify the restriction's particular owner through a public
     * API, so we never claim to know which administrator set it: on Android 11+
     * a managed setting is enough to choose USB, and an explicit platform
     * refusal is the final answer on any supported release.
     */
    val installBlockedObserved: Boolean = false,
)

/** The chosen update path, plus why. */
sealed class UpdateRoute {

    /** Install through the normal Android installer. */
    data class SystemInstaller(val reason: SystemInstallReason) : UpdateRoute()

    /**
     * Send the user to the "install unknown apps" screen exactly once. Only ever
     * chosen when that screen actually WORKS (no administrator owns it).
     */
    object UserGrantSettings : UpdateRoute()

    /**
     * Direct installation is unavailable. Offer the USB/ADB development path
     * instead. The user is NEVER sent to a settings switch that cannot be moved.
     */
    data class UsbAdb(val blocker: InstallBlocker) : UpdateRoute()
}

/** Why the normal installer is usable. */
enum class SystemInstallReason {
    /** No unknown-source restriction exists at all (pre-Android 8). */
    NOT_REQUIRED,

    /** The app already holds the per-app install grant. */
    GRANT_HELD,

    /** We are Device/Profile Owner and lifted our own restriction officially. */
    OWN_POLICY_EXEMPTION,
}

/**
 * THE single routing decision for every update attempt.
 *
 * The rule that fixes the reported bug lives here: once the "install unknown
 * apps" switch has been found to be unusable — because policy owns it, because
 * the platform has already refused an install, or because the user was already
 * sent there and it did not help — the answer is [UpdateRoute.UsbAdb] forever,
 * and the disabled switch is never shown again. That makes the
 * "Update Now → permission → Settings → blocked → back → same dialog" loop
 * structurally impossible rather than merely unlikely.
 *
 * SECURITY: nothing here bypasses, weakens or forges anything. It only chooses
 * between the official installer and an ADB install performed by the user's own
 * development computer. Owner status is never faked, and a restriction we do not
 * own is never cleared.
 */
object InstallRouteDecider {

    /** `Build.VERSION_CODES.O`: the first level with the unknown-sources grant. */
    const val FIRST_RESTRICTED_SDK = 26

    fun decide(env: InstallEnvironment): UpdateRoute = when {
        // Below Android 8 every app could sideload; there is nothing to grant.
        env.sdkInt < FIRST_RESTRICTED_SDK ->
            UpdateRoute.SystemInstaller(SystemInstallReason.NOT_REQUIRED)

        // The grant is already held: just install.
        env.canRequestInstalls ->
            UpdateRoute.SystemInstaller(SystemInstallReason.GRANT_HELD)

        // We are a legitimate Device/Profile Owner and we have already lifted OUR
        // OWN restriction through DevicePolicyManager. This is the platform's
        // documented managed flow, not a bypass.
        env.isDeviceOrProfileOwner && env.ownRestrictionLifted ->
            UpdateRoute.SystemInstaller(SystemInstallReason.OWN_POLICY_EXEMPTION)

        // An administrator owns the setting. The switch is disabled by policy and
        // there is nothing the app may legitimately do about it.
        env.unknownSourcesPolicyManaged ->
            UpdateRoute.UsbAdb(InstallBlocker.POLICY_MANAGED)

        // The platform already refused an install for us. Never try the settings
        // switch again: it is permanently disabled by policy.
        env.installBlockedObserved ->
            UpdateRoute.UsbAdb(InstallBlocker.POLICY_MANAGED)

        // Not granted, and not policy-managed: the settings screen genuinely
        // works, so offer it — but only ONCE. If it did not take effect we must
        // never loop the user back into it.
        env.alreadyPromptedForGrant ->
            UpdateRoute.UsbAdb(InstallBlocker.NOT_GRANTED)

        else -> UpdateRoute.UserGrantSettings
    }
}

/**
 * The honest, plain-language explanation for a blocked route. One pure mapping,
 * so what the user is told can never drift from what the app decided.
 */
fun UpdateRoute.blockerMessageRes(): Int = when (this) {
    is UpdateRoute.SystemInstaller -> R.string.update_blocker_none
    is UpdateRoute.UsbAdb -> when (blocker) {
        InstallBlocker.POLICY_MANAGED -> R.string.update_blocker_policy
        InstallBlocker.NOT_GRANTED -> R.string.update_blocker_not_granted
        InstallBlocker.NONE -> R.string.update_blocker_policy
    }
    UpdateRoute.UserGrantSettings -> R.string.update_blocker_none
}

/** True when this route needs the USB/ADB screen rather than the installer. */
val UpdateRoute.requiresUsbUpdate: Boolean
    get() = this is UpdateRoute.UsbAdb