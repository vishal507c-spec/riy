package com.vishal.riy.admin

import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.util.Log
import com.vishal.riy.blocker.BlockerStateStore

/**
 * Binds RIY's DNS filter as the Device Owner's always-on VPN.
 *
 * WHY THIS EXISTS: on several OEM ROMs (including the Infinix/Tecno "Griffin"
 * task killer) the filter process is killed when the app's task is removed,
 * and the app is then blocked from restarting itself. [BootReceiver] also races
 * the system VPN service during boot. A normal foreground service cannot win
 * either race. An always-on VPN is the ONE supported Android mechanism that
 * makes the SYSTEM — not the app — responsible for starting and keeping the
 * VPN alive, both at boot and after the process dies.
 *
 * This uses only the official `DevicePolicyManager` API and is idempotent.
 * It is a no-op when RIY is not the Device Owner, so the app still works on
 * devices provisioned without owner privileges (it just cannot self-heal).
 *
 * The user's persisted ON/OFF intent stays the single authority: this is
 * applied only while [BlockerStateStore.isProtectionWanted] is true, and
 * [clear] is the explicit OFF path.
 */
internal object AlwaysOnVpn {

    private const val TAG = "RiyAlwaysOnVpn"

    /**
     * Requests always-on VPN for RIY when protection is wanted.
     *
     * @return true only when the platform reports RIY as the always-on VPN
     *   package afterwards. A `false` is honest: the caller must still start
     *   the service, but it cannot rely on the system to resurrect it.
     */
    fun apply(context: Context): Boolean {
        val app = context.applicationContext
        if (!BlockerStateStore(app).isProtectionWanted()) {
            Log.i(TAG, "protection not wanted; always-on VPN left unchanged")
            return false
        }
        val dpm = app.getSystemService(DevicePolicyManager::class.java) ?: return false

        // During `dpm set-device-owner` the admin component can be enabled a
        // moment before the owner state is committed. Check first so this can
        // never crash a boot broadcast.
        @Suppress("DEPRECATION") // isDeviceOwnerApp is the API 24-compatible check.
        if (!dpm.isDeviceOwnerApp(app.packageName)) {
            Log.i(TAG, "not device owner; always-on VPN cannot be enforced")
            return false
        }

        val admin = ComponentName(app, RiyDeviceAdminReceiver::class.java)
        return try {
            dpm.setAlwaysOnVpnPackage(admin, app.packageName, false)
            val actual = dpm.getAlwaysOnVpnPackage(admin)
            val confirmed = actual == app.packageName
            Log.i(
                TAG,
                "always-on VPN requested=${app.packageName} actual=$actual confirmed=$confirmed",
            )
            confirmed
        } catch (e: SecurityException) {
            Log.e(TAG, "always-on VPN rejected by platform", e)
            false
        } catch (e: Exception) {
            // Unsupported OEM, VPN package not ready during boot, or another
            // always-on VPN owned by a different admin: never crash for it.
            Log.e(TAG, "could not set always-on VPN", e)
            false
        }
    }

    /**
     * Removes the always-on binding. Called only when the user explicitly
     * turns protection OFF; a reboot, task kill or transient revocation must
     * never take this path.
     */
    fun clear(context: Context): Boolean {
        val app = context.applicationContext
        val dpm = app.getSystemService(DevicePolicyManager::class.java) ?: return false
        @Suppress("DEPRECATION")
        if (!dpm.isDeviceOwnerApp(app.packageName)) return false
        val admin = ComponentName(app, RiyDeviceAdminReceiver::class.java)
        return try {
            dpm.setAlwaysOnVpnPackage(admin, null, false)
            Log.i(TAG, "always-on VPN cleared")
            true
        } catch (e: Exception) {
            Log.e(TAG, "could not clear always-on VPN", e)
            false
        }
    }
}
