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
 * This receiver owns NO policy itself: on every admin state change it funnels
 * into [com.vishal.riy.protection.integrity.ProtectionReconciler], the single
 * reconciliation entry point, so a Device Owner grant/revocation immediately
 * re-syncs platform enforcement with the persisted protection session (a live
 * restriction is re-applied, an expired one cleared, bypass apps swept).
 * Reconciliation never throws and honestly reports NOT_DEVICE_OWNER when the
 * privileges are gone, instead of pretending protection is active.
 */
class RiyDeviceAdminReceiver : DeviceAdminReceiver() {

    override fun onEnabled(context: Context, intent: Intent) {
        Log.i(TAG, "device admin enabled")
        // Applies immediately at provisioning time. If the owner state is not
        // committed yet this is a safe no-op; MainActivity re-applies it.
        UninstallProtection.apply(context)
        // Owner grant changes what the platform can enforce — reconcile now.
        runCatching {
            com.vishal.riy.protection.integrity.ProtectionReconciler.reconcileAll(
                context, "device_admin_enabled",
            )
        }
    }

    override fun onDisabled(context: Context, intent: Intent) {
        Log.i(TAG, "device admin disabled")
        // Owner/admin loss changes what is enforceable — reconcile so the
        // reported state stays honest (NOT_DEVICE_OWNER, never fake success).
        runCatching {
            com.vishal.riy.protection.integrity.ProtectionReconciler.reconcileAll(
                context, "device_admin_disabled",
            )
        }
    }

    private companion object {
        const val TAG = "RiyDeviceAdmin"
    }
}
