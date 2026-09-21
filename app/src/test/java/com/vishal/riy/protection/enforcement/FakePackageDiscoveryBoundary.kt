package com.vishal.riy.protection.enforcement

import com.vishal.riy.protection.enforcement.platform.DiscoveredPackage
import com.vishal.riy.protection.enforcement.platform.PackageDiscoveryBoundary

/**
 * JVM test double for [PackageDiscoveryBoundary]. It models an installed app
 * set in plain memory, so [PackageManagerAppPolicyResolver] can be proven
 * against realistic device contents WITHOUT a real `PackageManager`.
 *
 * Every app declares exactly the manifest facts the real platform would report
 * (intent actions, URI schemes, system flag, launcher presence), so the
 * resolver's classification logic is exercised truthfully.
 */
class FakePackageDiscoveryBoundary(

    /** The apps present on the modelled device. */
    private val apps: List<FakeApp> = emptyList(),

    /** The system default dialer, as TelecomManager would report it. */
    private val systemDialer: String? = null,

    /** The user's selected input method, as Settings.Secure would report it. */
    private val inputMethod: String? = null,

) : PackageDiscoveryBoundary {

    override fun isPackageInstalled(packageName: String): Boolean =
        apps.any { it.packageName == packageName }

    override fun isPackageEnabled(packageName: String): Boolean =
        apps.firstOrNull { it.packageName == packageName }?.enabled == true

    override fun hasLauncherActivity(packageName: String): Boolean =
        apps.firstOrNull { it.packageName == packageName }?.hasLauncher == true

    override fun queryIntentActivities(action: String, category: String?): List<DiscoveredPackage> =
        apps.asSequence()
            .filter { action in it.intentActions }
            .map { it.toDiscovered() }
            .toList()

    override fun queryUriSchemeActivities(action: String, uri: String): List<DiscoveredPackage> {
        val scheme = uri.substringBefore("://").takeIf { it.isNotBlank() } ?: return emptyList()
        return apps.asSequence()
            .filter { action in it.intentActions && scheme in it.uriSchemes }
            .map { it.toDiscovered() }
            .toList()
    }

    override fun resolveDefaultActivityPackage(action: String): String? =
        apps.firstOrNull { action in it.intentActions }?.packageName

    override fun systemDefaultDialerPackage(): String? = systemDialer

    override fun defaultInputMethodPackage(): String? = inputMethod

    override fun packageLabel(packageName: String): String? =
        apps.firstOrNull { it.packageName == packageName }?.label

    private fun FakeApp.toDiscovered() = DiscoveredPackage(
        packageName = packageName,
        isSystem = isSystem,
        label = label,
    )
}

/**
 * One modelled installed app and the manifest facts it declares.
 */
data class FakeApp(

    val packageName: String,

    val label: String = packageName,

    val isSystem: Boolean = false,

    val enabled: Boolean = true,

    val hasLauncher: Boolean = true,

    /** Actions this app declares an intent filter for. */
    val intentActions: Set<String> = emptySet(),

    /** URI schemes this app declares an intent filter for (wallet discovery). */
    val uriSchemes: Set<String> = emptySet(),

)

/** Small builder so a test reads like a device inventory. */
fun fakeDevice(
    systemDialer: String? = null,
    inputMethod: String? = null,
    block: FakeDeviceBuilder.() -> Unit,
): FakePackageDiscoveryBoundary = FakeDeviceBuilder(systemDialer, inputMethod).apply(block).build()

class FakeDeviceBuilder(

    private val systemDialer: String? = null,

    private val inputMethod: String? = null,

) {

    private val apps = mutableListOf<FakeApp>()

    /** RIY itself; present by default unless the test removes it. */
    fun riy(packageName: String, present: Boolean = true, enabled: Boolean = true) {
        if (present) apps += FakeApp(
            packageName = packageName,
            label = "RIY",
            isSystem = false,
            enabled = enabled,
            hasLauncher = true,
        )
    }

    /** A pre-installed phone app that declares DIAL + emergency dial. */
    fun phone(packageName: String = "com.device.dialer", withEmergency: Boolean = true) {
        apps += FakeApp(
            packageName = packageName,
            label = "Phone",
            isSystem = true,
            intentActions = buildSet {
                add(ACTION_DIAL)
                add(ACTION_CALL)
                if (withEmergency) add(ACTION_DIAL_EMERGENCY)
            },
        )
    }

    /** A wallet/payment app that declares a payment URI scheme. */
    fun wallet(packageName: String = "com.wallet.pay", scheme: String = "upi") {
        apps += FakeApp(
            packageName = packageName,
            label = "Wallet",
            isSystem = false,
            intentActions = setOf(ACTION_VIEW),
            uriSchemes = setOf(scheme),
        )
    }

    /** An ordinary app that policy must NOT auto-classify as anything. */
    fun ordinary(packageName: String = "com.example.distraction") {
        apps += FakeApp(
            packageName = packageName,
            label = "Distraction",
            isSystem = false,
            intentActions = setOf(ACTION_VIEW),
            uriSchemes = emptySet(),
        )
    }

    /** The keyboard the user has selected. */
    fun keyboard(packageName: String = "com.device.ime") {
        apps += FakeApp(
            packageName = packageName,
            label = "Keyboard",
            isSystem = true,
            hasLauncher = false,
        )
    }

    fun build(): FakePackageDiscoveryBoundary =
        FakePackageDiscoveryBoundary(apps.toList(), systemDialer, inputMethod)

    private companion object {
        const val ACTION_VIEW = "android.intent.action.VIEW"
        const val ACTION_DIAL = "android.intent.action.DIAL"
        const val ACTION_CALL = "android.intent.action.CALL"
        const val ACTION_DIAL_EMERGENCY = "android.intent.action.DIAL_EMERGENCY"
    }
}
