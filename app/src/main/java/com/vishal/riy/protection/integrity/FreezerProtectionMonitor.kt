package com.vishal.riy.protection.integrity

import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager

/**
 * Detects the real, observable package states an OEM "freezer"/power manager
 * can put an application into. It deliberately does NOT guess the freezer's
 * name or claim to read its private state: on this device the Infinix freezer
 * is a system_ext application whose whitelist and frozen-set live in
 * unreadable private storage, and it holds no Device Owner awareness.
 *
 * What this class CAN observe legitimately, through the public
 * [PackageManager] surface, is exactly the set of states a freezer is able to
 * impose: suspended, disabled/hidden, or the package being gone entirely.
 * Those are the signals it maps to a protection level.
 */
class FreezerProtectionMonitor(private val context: Context) {

    /** The outcome of one observation pass. */
    sealed interface ProtectionLevel {

        /** Installed, enabled, not suspended, and holding Device Owner. */
        data object Normal : ProtectionLevel

        /**
         * Installed and present, but in a degraded state — for example stopped
         * (the exact state a force-stop-based freezer produces). Protection is
         * not gone, but the user experience is.
         */
        data class Degraded(val reason: String) : ProtectionLevel

        /** The package is missing, disabled, hidden or no longer Device Owner. */
        data class Critical(val reason: String) : ProtectionLevel
    }

    /** Observes RIY's live package state and maps it to a protection level. */
    fun observe(): ProtectionLevel {
        val pm = context.packageManager

        val info = try {
            pm.getApplicationInfo(context.packageName, 0)
        } catch (_: PackageManager.NameNotFoundException) {
            return ProtectionLevel.Critical("RIY package is not installed")
        }

        if (!info.enabled) {
            return ProtectionLevel.Critical("RIY package is disabled")
        }

        // Stopped is the persistent mark a force-stop-based freezer leaves.
        // (FLAG_HIDDEN would also be relevant, but it was removed from the
        // public SDK, so only the observable stopped/suspended states are used.)
        val isStopped = info.flags and ApplicationInfo.FLAG_STOPPED != 0

        // Suspension is the modern OEM freezer mechanism (it conceals the
        // launcher entry while the package stays installed). API 29+ only.
        val isSuspended = if (android.os.Build.VERSION.SDK_INT >= 29) {
            try {
                pm.isPackageSuspended(context.packageName)
            } catch (_: PackageManager.NameNotFoundException) {
                return ProtectionLevel.Critical("RIY package is not installed")
            }
        } else {
            false
        }

        when {
            isSuspended -> return ProtectionLevel.Critical("RIY package is suspended")
            isStopped -> return ProtectionLevel.Degraded("RIY is force-stopped")
        }

        return ProtectionLevel.Normal
    }
}
