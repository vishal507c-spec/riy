package com.vishal.riy.blocker

import android.content.Context
import com.vishal.riy.admin.RiyDeviceAdminReceiver
import com.vishal.riy.protection.enforcement.platform.AndroidDevicePolicyBoundary
import com.vishal.riy.protection.enforcement.platform.AdminComponent
import com.vishal.riy.protection.enforcement.platform.DevicePolicyBoundary

/**
 * Closes the SYSTEM-level encrypted-DNS route using the one control Android
 * actually supports: the `no_config_private_dns` Device Owner user
 * restriction, which removes the "Private DNS" toggle so the system resolver
 * cannot be pointed at a DNS-over-TLS provider that would leave the filtered
 * VPN resolver.
 *
 * WHAT THIS DOES NOT DO — deliberately, and reported to the user rather than
 * hidden:
 *  - It does NOT intercept TLS. Nothing here decrypts a DoH query; a DoH QNAME
 *    stays inside TLS and therefore invisible to any DNS filter.
 *  - It does NOT reach a browser's own Secure DNS / DoH setting. Chrome's
 *    per-app encrypted resolver is not exposed by any public Android API, so no
 *    amount of Device Owner policy can stop it. See [EncryptedDnsExposure].
 *  - It is a no-op without Device Owner, is idempotent, and is reversible.
 */
object EncryptedDnsPolicy {

    /** Applies the restriction. Returns true only when it is genuinely in force. */
    fun apply(context: Context): Boolean = runCatching {
        val boundary = boundary(context)
        if (!boundary.isDeviceOwnerApp(context.packageName)) return false
        boundary.addUserRestriction(admin(context), DevicePolicyBoundary.RESTRICTION_CONFIG_PRIVATE_DNS)
        boundary.hasUserRestriction(admin(context), DevicePolicyBoundary.RESTRICTION_CONFIG_PRIVATE_DNS)
    }.getOrDefault(false)

    /** Reads back the live platform state rather than assuming it took. */
    fun isRestricted(context: Context): Boolean = runCatching {
        val boundary = boundary(context)
        if (!boundary.isDeviceOwnerApp(context.packageName)) return false
        boundary.hasUserRestriction(admin(context), DevicePolicyBoundary.RESTRICTION_CONFIG_PRIVATE_DNS)
    }.getOrDefault(false)

    private fun boundary(context: Context): DevicePolicyBoundary =
        AndroidDevicePolicyBoundary(context.applicationContext)

    private fun admin(context: Context): AdminComponent =
        AdminComponent(context.packageName, RiyDeviceAdminReceiver::class.java.name)
}