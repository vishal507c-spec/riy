package com.vishal.riy.protection.enforcement.platform

/**
 * One package discovered on the device, as raw platform fact. Classification
 * into an [com.vishal.riy.protection.enforcement.AllowedAppCategory] is done
 * later by policy — this value makes NO claim about what the package is for.
 *
 * @param packageName real installed package name.
 * @param isSystem    true only when the platform reports it as a system app.
 * @param label       the app's display label, or null when unavailable.
 */
data class DiscoveredPackage(

    val packageName: String,

    val isSystem: Boolean,

    val label: String?,
)

/**
 * THE seam in front of `android.content.pm.PackageManager` (and
 * `TelecomManager` / `Settings` for the couple of identity lookups that cannot
 * be answered by an intent query).
 *
 * Only [AndroidPackageDiscoveryBoundary] (and a JVM test fake) implements it.
 * The app policy resolver above this seam decides what a discovered package
 * MEANS; this boundary only reports what is physically present.
 */
interface PackageDiscoveryBoundary {

    /** True only if [packageName] is installed on this device. */
    fun isPackageInstalled(packageName: String): Boolean

    /** True only if [packageName] is installed AND enabled (not disabled). */
    fun isPackageEnabled(packageName: String): Boolean

    /** True only if [packageName] exposes a launcher (main) activity. */
    fun hasLauncherActivity(packageName: String): Boolean

    /**
     * Packages that declare an intent filter for [action] (optionally also
     * [category]), reported by the platform in priority order. Raw results —
     * the caller is responsible for any filtering policy.
     */
    fun queryIntentActivities(action: String, category: String?): List<DiscoveredPackage>

    /**
     * Packages that declare an intent filter for [action] able to handle [uri]
     * — the legitimate, manifest-declared way to discover apps by the URI
     * scheme they claim to support (used for wallet/payment discovery).
     */
    fun queryUriSchemeActivities(action: String, uri: String): List<DiscoveredPackage>

    /**
     * The single package that would handle [action] as the DEFAULT handler,
     * or null when nothing resolves. Used where policy must follow the one
     * app the system would actually launch (dialer, emergency dialer).
     */
    fun resolveDefaultActivityPackage(action: String): String?

    /**
     * The system's default dialer package (`TelecomManager`), or null when the
     * device reports none.
     */
    fun systemDefaultDialerPackage(): String?

    /**
     * The default input method (`Settings.Secure.DEFAULT_INPUT_METHOD`), as a
     * bare package name, or null when unset. The keyboard must keep working
     * under restriction, so policy needs the one the user actually has.
     */
    fun defaultInputMethodPackage(): String?

    /**
     * The platform's display label for [packageName], or null when the package
     * is not installed or has no label.
     */
    fun packageLabel(packageName: String): String?

    /**
     * Every installed package visible to RIY, as raw platform fact.
     * Used for default-deny reconciliation (installed-vs-allowed) and for
     * metadata-based bypass detection (e.g. TeraBox-family substring/label
     * matching). Classification into allowed/blocked stays with policy above
     * this seam — this only enumerates what is present.
     *
     * Default implementation returns empty so older fakes keep compiling; the
     * production boundary and current fakes override it.
     */
    fun installedPackages(): List<DiscoveredPackage> = emptyList()

    /**
     * True only if [packageName] was installed from an unknown source
     * (sideloaded APK) where the platform reports it. Best-effort: unknown
     * installers report false rather than throwing. Default false.
     */
    fun isUnknownSource(packageName: String): Boolean = false
}
