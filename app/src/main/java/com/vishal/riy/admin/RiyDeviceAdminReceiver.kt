package com.vishal.riy.admin

import android.app.admin.DeviceAdminReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

/**
 * RIY's Device Admin component. This is the anchor the Android system uses to
 * identify the app when it is provisioned as the Device Owner
 * (`dpm set-device-owner`). It is a BroadcastReceiver derived from
 * [android.app.admin.DeviceAdminReceiver]; the system (which holds
 * `android.permission.BIND_DEVICE_ADMIN`) is the only allowed binder.
 *
 * Deliberately minimal: no policies are enforced here yet. Later phases
 * (uninstall protection, app locking, ...) will build on this component.
 */
class RiyDeviceAdminReceiver : DeviceAdminReceiver() {

    override fun onEnabled(context: Context, intent: Intent) {
        Log.i(TAG, "device admin enabled")
        // Applies immediately at provisioning time. If the owner state is not
        // committed yet this is a safe no-op; MainActivity re-applies it.
        UninstallProtection.apply(context)
    }

    override fun onDisabled(context: Context, intent: Intent) {
        Log.i(TAG, "device admin disabled")
    }

    private companion object {
        const val TAG = "RiyDeviceAdmin"
    }
}
