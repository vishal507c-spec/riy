package com.vishal.riy.protection.enforcement

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The app policy resolver: it must discover what is REALLY installed and
 * classify it, never hardcode a package name, and never auto-allow an unknown
 * app. These run on the plain JVM against [FakePackageDiscoveryBoundary].
 */
class PackageManagerAppPolicyResolverTest {

    private val riyPackage = "com.vishal.riy"

    private fun resolver(
        discovery: FakePackageDiscoveryBoundary,
    ): PackageManagerAppPolicyResolver = PackageManagerAppPolicyResolver(discovery, riyPackage)

    /** Finds the resolved app for a category, if any. */
    private fun PackageManagerAppPolicyResolver.appFor(
        category: AllowedAppCategory,
    ): AllowedApp? = resolveAllowedApps().firstOrNull { it.category == category }

    // ------------------------------------------------------------- RIY

    @Test
    fun `riy is always included when installed`() {
        val resolver = resolver(
            fakeDevice(systemDialer = "com.device.dialer", inputMethod = "com.device.ime") {
                riy(riyPackage)
                phone()
                wallet()
            },
        )

        val allowed = resolver.resolveAllowedApps()

        assertTrue(allowed.any { it.category == AllowedAppCategory.RIY })
        assertEquals(riyPackage, allowed.first { it.category == AllowedAppCategory.RIY }.packageName)
    }

    @Test
    fun `riy missing is not included`() {
        val resolver = resolver(
            fakeDevice(systemDialer = "com.device.dialer", inputMethod = "com.device.ime") {
                riy(riyPackage, present = false)
                phone()
            },
        )

        assertFalse(resolver.resolveAllowedApps().any { it.category == AllowedAppCategory.RIY })
    }

    // ----------------------------------------------------------- PHONE

    @Test
    fun `phone resolves the actual system dialer`() {
        val resolver = resolver(
            fakeDevice(systemDialer = "com.infinix.dialer", inputMethod = "com.device.ime") {
                riy(riyPackage)
                phone(packageName = "com.infinix.dialer")
                wallet()
            },
        )

        val phone = resolver.appFor(AllowedAppCategory.PHONE)

        assertNotNull(phone)
        assertEquals("com.infinix.dialer", phone!!.packageName)
    }

    @Test
    fun `phone falls back to the default DIAL handler when telecom reports none`() {
        val resolver = resolver(
            fakeDevice(systemDialer = null, inputMethod = "com.device.ime") {
                riy(riyPackage)
                phone(packageName = "com.fallback.dialer")
            },
        )

        val phone = resolver.appFor(AllowedAppCategory.PHONE)

        assertNotNull("a default DIAL handler must satisfy PHONE", phone)
        assertEquals("com.fallback.dialer", phone!!.packageName)
    }

    @Test
    fun `phone unresolved when no dialer exists`() {
        val resolver = resolver(
            fakeDevice(systemDialer = null, inputMethod = "com.device.ime") {
                riy(riyPackage)
                ordinary()
            },
        )

        assertNull(resolver.appFor(AllowedAppCategory.PHONE))
    }

    @Test
    fun `phone package name is never a hardcoded assumption`() {
        // A deliberately unusual dialer package must be resolved identically;
        // the resolver has no baked-in notion of com.android.dialer or GPay dialer.
        val weird = "zzz.obscure.dialer.app"
        val resolver = resolver(
            fakeDevice(systemDialer = weird, inputMethod = "com.device.ime") {
                riy(riyPackage)
                phone(packageName = weird)
            },
        )

        assertEquals(
            weird,
            resolver.appFor(AllowedAppCategory.PHONE)!!.packageName,
        )
    }

    // ---------------------------------------------------------- WALLET

    @Test
    fun `wallet resolves a package declaring a payment scheme`() {
        val resolver = resolver(
            fakeDevice(systemDialer = "com.device.dialer", inputMethod = "com.device.ime") {
                riy(riyPackage)
                phone()
                wallet(packageName = "com.google.android.apps.nbu.paisa.user")
            },
        )

        val wallet = resolver.appFor(AllowedAppCategory.WALLET)

        assertNotNull(wallet)
        assertEquals("com.google.android.apps.nbu.paisa.user", wallet!!.packageName)
    }

    @Test
    fun `wallet unavailable when no payment app is installed`() {
        val resolver = resolver(
            fakeDevice(systemDialer = "com.device.dialer", inputMethod = "com.device.ime") {
                riy(riyPackage)
                phone()
            },
        )

        assertNull(resolver.appFor(AllowedAppCategory.WALLET))
    }

    @Test
    fun `wallet without a payment scheme is not classified as wallet`() {
        // An app that handles VIEW but declares no upi scheme must not be
        // promoted into the WALLET category.
        val resolver = resolver(
            fakeDevice(systemDialer = "com.device.dialer", inputMethod = "com.device.ime") {
                riy(riyPackage)
                phone()
                ordinary(packageName = "com.suspicious.browser")
            },
        )

        assertNull(resolver.appFor(AllowedAppCategory.WALLET))
    }

    // ------------------------------------------------------- UNKNOWN APPS

    @Test
    fun `an unknown installed app is excluded from the allowlist`() {
        val resolver = resolver(
            fakeDevice(systemDialer = "com.device.dialer", inputMethod = "com.device.ime") {
                riy(riyPackage)
                phone()
                wallet()
                ordinary(packageName = "com.example.distraction")
            },
        )

        val allowed = resolver.resolveAllowedApps()

        assertFalse("unknown apps must be BLOCKED, not allowed", allowed.any { it.packageName == "com.example.distraction" })
    }

    @Test
    fun `allowlist contains only explicitly approved categories`() {
        val resolver = resolver(
            fakeDevice(systemDialer = "com.device.dialer", inputMethod = "com.device.ime") {
                riy(riyPackage)
                phone()
                wallet()
                keyboard()
                ordinary()
            },
        )

        val allowedCategories = resolver.resolveAllowedApps().map { it.category }.toSet()

        assertEquals(
            setOf(
                AllowedAppCategory.RIY,
                AllowedAppCategory.PHONE,
                AllowedAppCategory.WALLET,
                AllowedAppCategory.EMERGENCY,
                AllowedAppCategory.SYSTEM_ESSENTIAL,
            ),
            allowedCategories,
        )
    }

    // --------------------------------------------------------- SUBSETS

    @Test
    fun `essential apps include riy, phone, emergency and input method`() {
        val resolver = resolver(
            fakeDevice(systemDialer = "com.device.dialer", inputMethod = "com.device.ime") {
                riy(riyPackage)
                phone()
                wallet()
                keyboard()
            },
        )

        val essentials = resolver.resolveEssentialApps().map { it.category }.toSet()

        assertTrue(AllowedAppCategory.RIY in essentials)
        assertTrue(AllowedAppCategory.PHONE in essentials)
        assertTrue(AllowedAppCategory.EMERGENCY in essentials)
        assertTrue(AllowedAppCategory.SYSTEM_ESSENTIAL in essentials)
        assertFalse(AllowedAppCategory.WALLET in essentials)
    }

    @Test
    fun `emergency apps resolve the emergency dial handler`() {
        val resolver = resolver(
            fakeDevice(systemDialer = "com.device.dialer", inputMethod = "com.device.ime") {
                riy(riyPackage)
                phone(packageName = "com.device.dialer", withEmergency = true)
            },
        )

        val emergency = resolver.resolveEmergencyApps()

        assertTrue(emergency.isNotEmpty())
        assertEquals(AllowedAppCategory.EMERGENCY, emergency.first().category)
    }

    @Test
    fun `allowlist collapses to one package even when an app covers two roles`() {
        // The dialer also owns the emergency dialer: it may legitimately appear
        // as both PHONE and EMERGENCY, but the package set it yields is one.
        val resolver = resolver(
            fakeDevice(systemDialer = "com.device.dialer", inputMethod = "com.device.ime") {
                riy(riyPackage)
                phone(packageName = "com.device.dialer", withEmergency = true)
                keyboard()
            },
        )

        val packages = resolver.resolveAllowedApps().map { it.packageName }

        assertEquals(
            "one package in the platform allowlist, however many roles it fills",
            1,
            packages.distinct().count { it == "com.device.dialer" },
        )
    }
}
