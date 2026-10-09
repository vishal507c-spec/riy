package com.vishal.riy.update

import android.app.admin.DevicePolicyManager
import android.content.Context
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.os.Build
import android.os.UserManager
import android.util.Log
import com.vishal.riy.admin.RiyDeviceAdminReceiver
import com.vishal.riy.protection.enforcement.platform.AdminComponent
import com.vishal.riy.protection.enforcement.platform.AndroidDevicePolicyBoundary
import com.vishal.riy.protection.enforcement.platform.DevicePolicyBoundary
import java.io.File
import java.security.MessageDigest

/**
 * Reads the REAL install environment from Android and turns it into the plain
 * [InstallEnvironment] the pure [InstallRouteDecider] reasons about.
 *
 * Every value here is a live platform read. Nothing is assumed, nothing is
 * cached across a policy change, and nothing is written except the one official
 * self-update exemption below.
 *
 * SECURITY CONTRACT:
 *  - we never modify a settings file, a system database or a hidden API;
 *  - we never grant ourselves the install permission;
 *  - we only ever clear `no_install_unknown_sources` when Android confirms THIS
 *    app is the Device/Profile Owner, because then the restriction is ours and
 *    `DevicePolicyManager.clearUserRestriction` is the documented API for it;
 *  - an administrator-owned restriction is detected and reported, never touched.
 */
object InstallEnvironmentProbe {

    private const val TAG = "RiyUpdateEnv"

    /** Gathers the environment. Never throws. */
    fun probe(
        context: Context,
        promptedForGrant: Boolean,
        installBlockedObserved: Boolean = false,
    ): InstallEnvironment {
        val app = context.applicationContext
        return InstallEnvironment(
            sdkInt = Build.VERSION.SDK_INT,
            canRequestInstalls = canRequestInstalls(app),
            unknownSourcesPolicyManaged = isUnknownSourcesSystemBlocked(app),
            isDeviceOrProfileOwner = isDeviceOrProfileOwner(app),
            // Deliberately false until the caller has legitimately applied the
            // exemption through [allowOwnUpdateExemption]; nothing else may set it.
            ownRestrictionLifted = false,
            alreadyPromptedForGrant = promptedForGrant,
            installBlockedObserved = installBlockedObserved,
        )
    }

    fun canRequestInstalls(context: Context): Boolean =
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            true // the restriction does not exist before Android 8
        } else {
            runCatching { context.packageManager.canRequestPackageInstalls() }
                .getOrDefault(false)
        }

    /**
     * True when Android reports the system-level unknown-sources restriction as
     * active. Only a device/profile owner can set this restriction, so for an
     * app that cannot already install, an active restriction means the settings
     * switch is disabled and should not be offered. When this app is itself the
     * owner, [allowOwnUpdateExemption] gets a chance to clear its own
     * restriction first and then re-probes this value.
     */
    fun isUnknownSourcesSystemBlocked(context: Context): Boolean {
        return try {
            val userManager = context.getSystemService(UserManager::class.java) ?: return false
            userManager.hasUserRestriction(UserManager.DISALLOW_INSTALL_UNKNOWN_SOURCES)
        } catch (e: Exception) {
            Log.w(TAG, "restriction check failed: ${e.javaClass.simpleName}")
            false
        }
    }

    /** True only when Android reports this app as the Device OR Profile Owner. */
    fun isDeviceOrProfileOwner(context: Context): Boolean {
        val dpm = context.getSystemService(DevicePolicyManager::class.java) ?: return false
        val pkg = context.packageName
        return try {
            val deviceOwner = dpm.isDeviceOwnerApp(pkg)
            @Suppress("DEPRECATION") // isProfileOwnerApp is the API 21-compatible check.
            val profileOwner = dpm.isProfileOwnerApp(pkg)
            deviceOwner || profileOwner
        } catch (e: Exception) {
            Log.w(TAG, "owner check failed: ${e.javaClass.simpleName}")
            false
        }
    }

    /**
     * The ONE official, in-policy thing this app may do to update itself.
     *
     * RIY's own managed hardening sets `no_install_unknown_sources` while
     * protection is active, and the system reports that to the user as
     * "Blocked by your IT admin" — even for RIY's own verified update, because a
     * per-app grant can never override a managed restriction.
     *
     * When (and only when) Android confirms RIY is the Device/Profile Owner, that
     * restriction is OURS, so `clearUserRestriction` is the correct and documented
     * way to lift it for this verified, self-signed update. Scope is one
     * restriction, the window is momentary, and the next reconciliation re-raises
     * it. Non-owners are a safe no-op.
     *
     * @return the environment re-probed afterwards, so the caller routes on facts
     *         that were true AFTER the exemption rather than before it.
     */
    fun allowOwnUpdateExemption(
        context: Context,
        promptedForGrant: Boolean,
        installBlockedObserved: Boolean = false,
    ): InstallEnvironment {
        val app = context.applicationContext
        var lifted = false
        try {
            if (!isDeviceOrProfileOwner(app)) {
                Log.i(TAG, "self-update exemption skipped: RIY is not Device/Profile Owner")
                return probe(app, promptedForGrant, installBlockedObserved)
            }
            val boundary = AndroidDevicePolicyBoundary(app)
            val admin = AdminComponent(
                app.packageName,
                RiyDeviceAdminReceiver::class.java.name,
            )
            lifted = boundary.clearUserRestriction(
                admin,
                DevicePolicyBoundary.RESTRICTION_INSTALL_UNKNOWN_SOURCES,
            )
            Log.i(TAG, "own unknown-sources restriction cleared=$lifted")
        } catch (e: Exception) {
            Log.w(TAG, "self-update exemption failed: ${e.javaClass.simpleName}")
            lifted = false
        }
        return probe(app, promptedForGrant, installBlockedObserved).copy(ownRestrictionLifted = lifted)
    }

    // ------------------------------------------------------------- APK facts

    /** Facts about the INSTALLED app (always readable). */
    fun installedApkFacts(context: Context): ApkFacts {
        val pm = context.packageManager
        return try {
            val info = packageInfoWithSignatures(pm, context.packageName, null)
                ?: return ApkFacts.UNREADABLE
            ApkFacts(
                packageName = info.packageName,
                versionCode = versionCodeOf(info),
                versionName = info.versionName,
                signerSha256 = signerSha256Of(info),
                readable = true,
                sizeBytes = 0L,
            )
        } catch (e: Exception) {
            Log.w(TAG, "installed facts unreadable: ${e.javaClass.simpleName}")
            ApkFacts.UNREADABLE
        }
    }

    /**
     * Facts about a candidate APK, read with `getPackageArchiveInfo` — WITHOUT
     * installing anything. Returns [ApkFacts.UNREADABLE] for a missing, truncated
     * or otherwise corrupt archive, which is how a corrupted download is caught
     * before it ever reaches the installer.
     */
    fun candidateApkFacts(context: Context, apkFile: File): ApkFacts {
        if (!apkFile.exists() || apkFile.length() <= 0L) return ApkFacts.UNREADABLE
        return try {
            val pm = context.packageManager
            val info = packageInfoWithSignatures(pm, null, apkFile.absolutePath)
                ?: return ApkFacts.UNREADABLE
            ApkFacts(
                packageName = info.packageName,
                versionCode = versionCodeOf(info),
                versionName = info.versionName,
                signerSha256 = signerSha256Of(info),
                readable = true,
                sizeBytes = apkFile.length(),
            )
        } catch (e: Exception) {
            Log.w(TAG, "candidate facts unreadable: ${e.javaClass.simpleName}")
            ApkFacts.UNREADABLE
        }
    }

    /**
     * Requests signatures with the newest API the running release supports. The
     * constants are read only inside their matching version branches, so merely
     * loading this class on Android 7 can never touch an API 28+ field.
     */
    private fun packageInfoWithSignatures(
        pm: PackageManager,
        packageName: String?,
        archivePath: String?,
    ): PackageInfo? {
        require((packageName == null) != (archivePath == null)) {
            "Exactly one of packageName and archivePath is required"
        }
        return when {
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU -> {
                val flags = PackageManager.PackageInfoFlags.of(
                    PackageManager.GET_SIGNING_CERTIFICATES.toLong(),
                )
                if (archivePath != null) {
                    pm.getPackageArchiveInfo(archivePath, flags)
                } else {
                    pm.getPackageInfo(requireNotNull(packageName), flags)
                }
            }

            Build.VERSION.SDK_INT >= Build.VERSION_CODES.P -> {
                @Suppress("DEPRECATION")
                val flags = PackageManager.GET_SIGNING_CERTIFICATES
                if (archivePath != null) {
                    pm.getPackageArchiveInfo(archivePath, flags)
                } else {
                    pm.getPackageInfo(requireNotNull(packageName), flags)
                }
            }

            else -> {
                @Suppress("DEPRECATION")
                val flags = PackageManager.GET_SIGNATURES
                if (archivePath != null) {
                    pm.getPackageArchiveInfo(archivePath, flags)
                } else {
                    pm.getPackageInfo(requireNotNull(packageName), flags)
                }
            }
        }
    }

    private fun versionCodeOf(info: PackageInfo): Long =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            info.longVersionCode
        } else {
            @Suppress("DEPRECATION")
            info.versionCode.toLong()
        }

    /**
     * SHA-256 of the certificate the APK was signed with. Comparing this between
     * the installed app and the candidate is exactly the check Android performs
     * at install time, performed early enough to explain itself.
     */
    @Suppress("DEPRECATION")
    private fun signerSha256Of(info: PackageInfo): String? {
        val bytes: ByteArray? = try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                val signing = info.signingInfo ?: return null
                // Multiple signers mean the lineage/capability path is in play; the
                // first current signer is the identity Android will compare.
                signing.apkContentsSigners?.firstOrNull()?.toByteArray()
                    ?: signing.signingCertificateHistory?.firstOrNull()?.toByteArray()
            } else {
                info.signatures?.firstOrNull()?.toByteArray()
            }
        } catch (_: Exception) {
            null
        }
        val key = bytes ?: return null
        return try {
            MessageDigest.getInstance("SHA-256").digest(key).joinToString("") { "%02x".format(it) }
        } catch (_: Exception) {
            null
        }
    }
}