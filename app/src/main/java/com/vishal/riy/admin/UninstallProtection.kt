package com.vishal.riy.admin

import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.util.Log

/**
 * Marks RIY's own package as uninstall-blocked using the Device Owner
 * privileges held by [RiyDeviceAdminReceiver]. Once applied, Android refuses
 * uninstallation both from the Settings UI and via `pm uninstall` (rejected by
 * DevicePolicyManagerService).
 *
 * This uses only the official android.app.admin.DevicePolicyManager API. No
 * system-image modification, no exploit, no companion app.
 */
internal object UninstallProtection {

    private const val TAG = "RiyUninstallGuard"

    /**
     * Marks RIY as uninstall-blocked. Idempotent and safe to call repeatedly;
     * it is a no-op when RIY is not (yet) the Device Owner, so it can be called
     * both from the admin receiver and defensively at app launch.
     */
    fun apply(context: Context) {
        val dpm = context.getSystemService(DevicePolicyManager::class.java) ?: return
        val admin = ComponentName(context, RiyDeviceAdminReceiver::class.java)

        // During `dpm set-device-owner` the admin component can be enabled a
        // moment before the owner state is committed, and setUninstallBlocked
        // throws SecurityException unless the caller is the device owner.
        @Suppress("DEPRECATION") // isDeviceOwnerApp is the API 24-compatible check.
        if (!dpm.isDeviceOwnerApp(context.packageName)) {
            Log.i(TAG, "not device owner; uninstall protection skipped")
            return
        }
        try {
            dpm.setUninstallBlocked(admin, context.packageName, true)
            Log.i(TAG, "uninstall protection ON for ${context.packageName}")
        } catch (e: SecurityException) {
            Log.e(TAG, "failed to set uninstall protection", e)
        }
    }
}
