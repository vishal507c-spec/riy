package com.vishal.riy.protection.enforcement.platform

import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.telecom.TelecomManager
import android.util.Log

/**
 * The production [PackageDiscoveryBoundary]: a stateless adapter over the real
 * `PackageManager`, plus the `TelecomManager` and `Settings` lookups needed for
 * the few identities an intent query cannot answer.
 *
 * It reports raw platform fact only. Whether a discovered package is ALLOWED
 * is a policy decision made above this seam, never here. It never grants,
 * suspends, hides or launches anything.
 */
class AndroidPackageDiscoveryBoundary(

    private val context: Context,

) : PackageDiscoveryBoundary {

    private val pm: PackageManager = context.packageManager

    override fun isPackageInstalled(packageName: String): Boolean = try {
        // A package present but disabled still resolves here; the enabled state
        // is checked separately by [isPackageEnabled].
        pm.getPackageInfo(packageName, 0) != null
    } catch (_: PackageManager.NameNotFoundException) {
        false
    }

    override fun isPackageEnabled(packageName: String): Boolean = try {
        pm.getApplicationInfo(packageName, 0).enabled
    } catch (_: PackageManager.NameNotFoundException) {
        false
    }

    override fun hasLauncherActivity(packageName: String): Boolean =
        pm.getLaunchIntentForPackage(packageName) != null

    override fun queryIntentActivities(action: String, category: String?): List<DiscoveredPackage> {
        val intent = Intent(action)
        if (category != null) intent.addCategory(category)
        return query(intent)
    }

    override fun queryUriSchemeActivities(action: String, uri: String): List<DiscoveredPackage> =
        query(Intent(action, Uri.parse(uri)))

    override fun resolveDefaultActivityPackage(action: String): String? = try {
        val intent = Intent(action)
        @Suppress("DEPRECATION") // MATCH_DEFAULT_ONLY: follow the app the system would launch.
        pm.resolveActivity(intent, PackageManager.MATCH_DEFAULT_ONLY)?.activityInfo?.packageName
    } catch (e: Exception) {
        Log.e(TAG, "resolveDefaultActivityPackage failed for $action", e)
        null
    }

    override fun systemDefaultDialerPackage(): String? = try {
        val telecom = context.getSystemService(TelecomManager::class.java) ?: return null
        // The SYSTEM dialer — the pre-installed, trustworthy phone app — not a
        // user-downloaded dialer that policy has no reason to trust.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            telecom.systemDialerPackage
        } else {
            @Suppress("DEPRECATION") // the only dialer identity available pre-API 29
            telecom.defaultDialerPackage
        }
    } catch (e: Exception) {
        Log.e(TAG, "systemDefaultDialerPackage failed", e)
        null
    }

    override fun defaultInputMethodPackage(): String? = try {
        // The keyboard the user actually has selected, so a restriction can
        // never silently remove the ability to type. This reads a public
        // secure setting; it requires no permission.
        val im = Settings.Secure.getString(
            context.contentResolver,
            Settings.Secure.DEFAULT_INPUT_METHOD,
        ) ?: return null
        // Stored as "<package>/<service class>"; policy needs only the package.
        im.substringBefore('/').takeIf { it.isNotBlank() }
    } catch (e: Exception) {
        Log.e(TAG, "defaultInputMethodPackage failed", e)
        null
    }

    override fun packageLabel(packageName: String): String? = labelOf(packageName)

    // ------------------------------------------------------------------ priv

    private fun query(intent: Intent): List<DiscoveredPackage> = try {
        @Suppress("DEPRECATION") // MATCH_ALL is the deliberate, explicit selector here.
        pm.queryIntentActivities(intent, PackageManager.MATCH_ALL)
            .asSequence()
            .map { it.activityInfo.packageName }
            .distinct()
            .map { pkg -> DiscoveredPackage(pkg, isSystemPackage(pkg), labelOf(pkg)) }
            .toList()
    } catch (e: Exception) {
        // A malformed intent or a dead package must never break discovery; the
        // resolver treats an empty result as "unresolved" and reports it.
        Log.e(TAG, "intent query failed for ${intent.action}", e)
        emptyList()
    }

    /** True only when the platform marks [packageName] as a system app. */
    private fun isSystemPackage(packageName: String): Boolean = try {
        pm.getApplicationInfo(packageName, 0).flags and ApplicationInfo.FLAG_SYSTEM != 0
    } catch (_: PackageManager.NameNotFoundException) {
        false
    }

    private fun labelOf(packageName: String): String? = try {
        pm.getApplicationLabel(pm.getApplicationInfo(packageName, 0))?.toString()
    } catch (_: PackageManager.NameNotFoundException) {
        null
    }

    private companion object {
        const val TAG = "RiyPmBoundary"
    }
}
