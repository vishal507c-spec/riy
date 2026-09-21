package com.vishal.riy.protection.enforcement

import com.vishal.riy.protection.enforcement.platform.PackageDiscoveryBoundary

/**
 * THE implementation of [AppPolicyResolver]. It discovers what is genuinely
 * installed and classifies it into an explicit, closed set of categories.
 *
 * WHAT IT IS ALLOWED TO CONCLUDE
 * ------------------------------
 * A package enters [resolveAllowedApps] only by satisfying one of the
 * category rules below. There is no default-allow: anything the resolver
 * cannot positively classify is simply absent from the result, which means
 * policy treats it as BLOCKED. Unknown apps are never "essential".
 *
 * HOW EACH CATEGORY IS RESOLVED (never hardcoded)
 * ------------------------------
 *  - RIY         : the app's own package, verified installed.
 *  - PHONE       : `TelecomManager.systemDefaultDialerPackage`, falling back to
 *                  the platform's DEFAULT handler of the DIAL action. Whichever
 *                   package is reported is verified to be installed.
 *  - WALLET      : packages that DECLARE, in their own manifest, an intent
 *                  filter for a payment URI scheme (default: `upi`) AND expose
 *                   a launcher activity. This is legitimate package metadata —
 *                   no wallet package name is hardcoded anywhere. When nothing
 *                   matches, the wallet is reported as unresolved (absent from
 *                   the list); policy copes without it.
 *  - EMERGENCY   : the platform's default handler of the emergency-dial action.
 *  - SYSTEM_ESSENTIAL : the user's selected input method, so restriction can
 *                  never take away the ability to type.
 *
 * The strings below are the documented Android action values, spelled out as
 * plain Kotlin constants so that this class stays free of `android.*` imports
 * and remains unit-testable on the JVM (where `Intent.ACTION_*` would resolve
 * to null). Only the boundary turns them into real Intents.
 */
class PackageManagerAppPolicyResolver(

    private val discovery: PackageDiscoveryBoundary,

    private val riyPackageName: String,

    private val walletUriSchemes: List<String> = DEFAULT_WALLET_SCHEMES,

) : AppPolicyResolver {

    /**
     * The complete allowlist: every package policy keeps available during a
     * restriction. A package that legitimately covers two roles (the phone app
     * that also owns the emergency dialer, for instance) may appear once per
     * role — the package-level dedup the enforcement engine performs when it
     * builds the platform allowlist is what collapses those to one package.
     */
    override fun resolveAllowedApps(): List<AllowedApp> =
        dedupByPackageAndCategory(resolveEssentialApps() + resolveWalletApps())

    /**
     * What the device genuinely needs to remain usable: RIY (so the UI can keep
     * showing), the phone, the emergency dialer and the keyboard.
     */
    override fun resolveEssentialApps(): List<AllowedApp> = dedupByPackageAndCategory(
        buildList {
            resolveRiy()?.let { add(it) }
            resolvePhone()?.let { add(it) }
            resolveEmergency()?.let { add(it) }
            resolveInputMethod()?.let { add(it) }
        },
    )

    /** The subset providing emergency calling. */
    override fun resolveEmergencyApps(): List<AllowedApp> =
        dedupByPackageAndCategory(listOfNotNull(resolveEmergency()))

    // ------------------------------------------------------------- RIY

    /** RIY is always included, but only after it is verified installed. */
    private fun resolveRiy(): AllowedApp? {
        if (!discovery.isPackageInstalled(riyPackageName)) return null
        return AllowedApp(
            packageName = riyPackageName,
            displayName = discovery.packageLabel(riyPackageName) ?: "RIY",
            category = AllowedAppCategory.RIY,
        )
    }

    // ------------------------------------------------------------ PHONE

    /**
     * The actual default dialer. Prefers the SYSTEM default (a pre-installed,
     * trustworthy phone app); falls back to whichever app the platform would
     * launch for DIAL, then CALL. Never a hardcoded package name.
     */
    private fun resolvePhone(): AllowedApp? {
        val pkg = discovery.systemDefaultDialerPackage()
            ?: discovery.resolveDefaultActivityPackage(ACTION_DIAL)
            ?: discovery.resolveDefaultActivityPackage(ACTION_CALL)
            ?: return null

        // The package must be real, installed, and not RIY itself.
        if (!isSelectable(pkg)) return null

        // It must genuinely declare dial capability — this is what stops an
        // arbitrary app that happened to be the fallback from being allowed.
        if (!canDial(pkg)) return null

        return AllowedApp(
            packageName = pkg,
            displayName = discovery.packageLabel(pkg) ?: pkg,
            category = AllowedAppCategory.PHONE,
        )
    }

    // ----------------------------------------------------------- WALLET

    /**
     * Every wallet/payment app that can be POSITIVELY identified: it declares a
     * filter for a known payment URI scheme and is a real, launchable app.
     *
     * Nothing is invented. If the device has no such app the result is empty
     * and policy proceeds without a wallet (reported as unresolved).
     */
    private fun resolveWalletApps(): List<AllowedApp> {
        val candidates = linkedSetOf<String>()
        walletUriSchemes.forEach { scheme ->
            discovery.queryUriSchemeActivities(ACTION_VIEW, "$scheme://")
                .asSequence()
                .map { it.packageName }
                .forEach { candidates += it }
        }

        return candidates
            .filter { isSelectable(it) && discovery.hasLauncherActivity(it) }
            .map {
                AllowedApp(
                    packageName = it,
                    displayName = discovery.packageLabel(it) ?: it,
                    category = AllowedAppCategory.WALLET,
                )
            }
    }

    // --------------------------------------------------------- EMERGENCY

    /** The platform's default emergency-dial handler, verified installed. */
    private fun resolveEmergency(): AllowedApp? {
        val pkg = discovery.resolveDefaultActivityPackage(ACTION_DIAL_EMERGENCY)
            ?: return null
        if (!isSelectable(pkg)) return null
        return AllowedApp(
            packageName = pkg,
            displayName = discovery.packageLabel(pkg) ?: pkg,
            category = AllowedAppCategory.EMERGENCY,
        )
    }

    // --------------------------------------------------- SYSTEM ESSENTIAL

    /** The user's actual keyboard, so typing stays possible under restriction. */
    private fun resolveInputMethod(): AllowedApp? {
        val pkg = discovery.defaultInputMethodPackage() ?: return null
        if (!isSelectable(pkg)) return null
        return AllowedApp(
            packageName = pkg,
            displayName = discovery.packageLabel(pkg) ?: pkg,
            category = AllowedAppCategory.SYSTEM_ESSENTIAL,
        )
    }

    // ------------------------------------------------------------- utils

    /**
     * A package is selectable only when it is genuinely installed, enabled, and
     * is not RIY (RIY enters through its own dedicated category).
     */
    private fun isSelectable(packageName: String): Boolean =
        packageName != riyPackageName &&
            discovery.isPackageInstalled(packageName) &&
            discovery.isPackageEnabled(packageName)

    /** True only when [packageName] declares a DIAL or CALL intent filter. */
    private fun canDial(packageName: String): Boolean =
        (discovery.queryIntentActivities(ACTION_DIAL, null).map { it.packageName } +
            discovery.queryIntentActivities(ACTION_CALL, null).map { it.packageName })
            .contains(packageName)

    /** Keeps the first entry for any duplicated (package, category) pair. */
    private fun dedupByPackageAndCategory(apps: List<AllowedApp>): List<AllowedApp> {
        val seen = mutableSetOf<Pair<String, AllowedAppCategory>>()
        return apps.filter { seen.add(it.packageName to it.category) }
    }

    private companion object {

        // Documented Android action values, as plain strings so that no
        // android.* import is needed and JVM tests are meaningful.
        const val ACTION_VIEW = "android.intent.action.VIEW"
        const val ACTION_DIAL = "android.intent.action.DIAL"
        const val ACTION_CALL = "android.intent.action.CALL"
        const val ACTION_DIAL_EMERGENCY = "android.intent.action.DIAL_EMERGENCY"

        /**
         * URI schemes a wallet/payment app declares in its own manifest. `upi`
         * is the standard scheme declared by India's UPI payment apps, which is
         * what this device class ships. Adding a scheme here is an explicit,
         * auditable policy decision — never a package name.
         */
        val DEFAULT_WALLET_SCHEMES: List<String> = listOf("upi")
    }
}
