package com.vishal.riy.protection.enforcement

/**
 * Category of an application the policy may allow during a restriction.
 *
 * NOTE: [AppPolicyResolver] must resolve REAL installed packages into these
 * categories at runtime. These values are never a substitute for verifying
 * that a package is genuinely present on the device.
 */
enum class AllowedAppCategory {

    /** RIY itself (always allowed so it can keep showing the restriction UI). */
    RIY,

    /** The device's dialer/phone app, so calls remain possible. */
    PHONE,

    /** A wallet/payment app explicitly approved by policy (for example GPay). */
    WALLET,

    /**
     * A legitimate communication app explicitly approved by policy (Telegram).
     * Resolved from verified installed package metadata (known Telegram
     * identities that are installed + enabled + launcher-backed). Telegram
     * content itself is never inspected — this category only keeps the
     * messenger launchable during a restriction.
     */
    COMMUNICATION,

    /** System functionality required for the device to remain usable. */
    SYSTEM_ESSENTIAL,

    /** Emergency functionality (for example the emergency dialer). */
    EMERGENCY,
}

/**
 * Immutable record of one application the policy resolved as allowed.
 *
 * Pure Kotlin — no PackageManager reference. The resolver that produces these
 * is the only component allowed to query the installed apps.
 *
 * @param packageName  the real installed package name (never a placeholder).
 * @param displayName  a label for the UI.
 * @param category     why it is allowed.
 */
data class AllowedApp(

    val packageName: String,

    val displayName: String,

    val category: AllowedAppCategory,
)
