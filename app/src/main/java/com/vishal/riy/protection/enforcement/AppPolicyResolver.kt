package com.vishal.riy.protection.enforcement

/**
 * Resolves, at runtime, which REAL installed applications policy must keep
 * available during a restriction.
 *
 * HARD RULE: implementations must query the actual device (PackageManager) and
 * may NOT hardcode package names such as a fictional "com.example.phone". A
 * package is only ever reported when it is genuinely installed. What is allowed
 * is decided by policy; what is present is decided by the device.
 *
 * Phase 2 establishes the contract only; PackageManager discovery arrives later.
 */
interface AppPolicyResolver {

    /** Every application policy keeps available during a restriction. */
    fun resolveAllowedApps(): List<AllowedApp>

    /** The subset required for the device to remain usable (system essentials + phone). */
    fun resolveEssentialApps(): List<AllowedApp>

    /** The subset providing emergency functionality. */
    fun resolveEmergencyApps(): List<AllowedApp>
}
