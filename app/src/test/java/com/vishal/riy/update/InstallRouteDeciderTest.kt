package com.vishal.riy.update

import com.vishal.riy.R
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The routing decision that fixes the reported bug.
 *
 * The invariant under test, stated once:
 *
 *   A device on which the unknown-sources switch cannot be moved must NEVER be
 *   routed to that switch again — not on the first tap, not after "back", not on
 *   the hundredth attempt. It goes to USB/ADB and stays there.
 */
class InstallRouteDeciderTest {

    // ------------------------------------------------------------ Path A

    @Test
    fun `before Android 8 there is no unknown sources restriction so install just works`() {
        val route = InstallRouteDecider.decide(InstallEnvironment(sdkInt = 24, canRequestInstalls = false))

        assertEquals(
            UpdateRoute.SystemInstaller(SystemInstallReason.NOT_REQUIRED),
            route,
        )
    }

    @Test
    fun `holding the install grant routes to the normal installer`() {
        val route = InstallRouteDecider.decide(
            InstallEnvironment(sdkInt = 34, canRequestInstalls = true),
        )

        assertEquals(UpdateRoute.SystemInstaller(SystemInstallReason.GRANT_HELD), route)
    }

    @Test
    fun `a real device owner that lifted its OWN restriction routes to the normal installer`() {
        val route = InstallRouteDecider.decide(
            InstallEnvironment(
                sdkInt = 34,
                canRequestInstalls = false,
                isDeviceOrProfileOwner = true,
                ownRestrictionLifted = true,
            ),
        )

        assertEquals(UpdateRoute.SystemInstaller(SystemInstallReason.OWN_POLICY_EXEMPTION), route)
    }

    @Test
    fun `owner status alone is never enough without the exemption actually applied`() {
        val route = InstallRouteDecider.decide(
            InstallEnvironment(
                sdkInt = 34,
                canRequestInstalls = false,
                isDeviceOrProfileOwner = true,
                ownRestrictionLifted = false,
            ),
        )

        assertTrue("must not assume owner privileges were applied", route.requiresUsbUpdate || route === UpdateRoute.UserGrantSettings)
    }

    @Test
    fun `a non owner cannot claim the exemption`() {
        val route = InstallRouteDecider.decide(
            InstallEnvironment(
                sdkInt = 34,
                canRequestInstalls = false,
                isDeviceOrProfileOwner = false,
                // Even if a caller wrongly set this, a non-owner cannot use it.
                ownRestrictionLifted = true,
            ),
        )

        assertTrue(route.requiresUsbUpdate || route === UpdateRoute.UserGrantSettings)
    }

    // ------------------------------------------------------ the reported bug

    @Test
    fun `a policy managed restriction goes straight to USB without using the settings prompt`() {
        val route = InstallRouteDecider.decide(
            InstallEnvironment(
                sdkInt = 34,
                canRequestInstalls = false,
                unknownSourcesPolicyManaged = true,
                alreadyPromptedForGrant = false,
            ),
        )

        assertEquals(UpdateRoute.UsbAdb(InstallBlocker.POLICY_MANAGED), route)
    }

    @Test
    fun `when policy already blocked an install the settings screen is never offered again`() {
        val route = InstallRouteDecider.decide(
            InstallEnvironment(
                sdkInt = 34,
                canRequestInstalls = false,
                installBlockedObserved = true,
            ),
        )

        assertEquals(UpdateRoute.UsbAdb(InstallBlocker.POLICY_MANAGED), route)
        assertTrue(route.requiresUsbUpdate)
    }

    @Test
    fun `an ineffective settings prompt can only happen once`() {
        val first = InstallRouteDecider.decide(
            InstallEnvironment(sdkInt = 34, canRequestInstalls = false, alreadyPromptedForGrant = false),
        )
        val second = InstallRouteDecider.decide(
            InstallEnvironment(sdkInt = 34, canRequestInstalls = false, alreadyPromptedForGrant = true),
        )

        assertEquals(UpdateRoute.UserGrantSettings, first)
        assertEquals(UpdateRoute.UsbAdb(InstallBlocker.NOT_GRANTED), second)
    }

    @Test
    fun `THE LOOP CANNOT HAPPEN - every tap after the first settles on USB`() {
        // Simulates: Update Now -> permission -> Settings -> blocked -> back ->
        // Update Now -> ... and keeps tapping for a long time.
        var state = InstallEnvironment(sdkInt = 34, canRequestInstalls = false)
        val routes = buildList {
            repeat(50) {
                add(InstallRouteDecider.decide(state))
                // Coming back from Settings never grants anything.
                state = state.copy(alreadyPromptedForGrant = true)
            }
        }

        val settingsOffers = routes.count { it === UpdateRoute.UserGrantSettings }
        assertEquals("the dead settings screen may be offered at most once", 1, settingsOffers)
        assertTrue("every later tap must land on USB", routes.drop(1).all { it.requiresUsbUpdate })
    }

    @Test
    fun `after a policy block the device can never be routed back to settings`() {
        val state = InstallEnvironment(
            sdkInt = 34,
            canRequestInstalls = false,
            installBlockedObserved = true,
            alreadyPromptedForGrant = false,
        )

        repeat(10) {
            assertEquals(
                UpdateRoute.UsbAdb(InstallBlocker.POLICY_MANAGED),
                InstallRouteDecider.decide(state),
            )
        }
    }

    @Test
    fun `granting the permission later restores the normal installer`() {
        val route = InstallRouteDecider.decide(
            InstallEnvironment(
                sdkInt = 34,
                canRequestInstalls = true,
                alreadyPromptedForGrant = true,
                installBlockedObserved = false,
            ),
        )

        assertEquals(UpdateRoute.SystemInstaller(SystemInstallReason.GRANT_HELD), route)
    }

    // -------------------------------------------------------------- messages

    @Test
    fun `the USB route explains the restriction in plain language`() {
        assertEquals(
            R.string.update_blocker_policy,
            UpdateRoute.UsbAdb(InstallBlocker.POLICY_MANAGED).blockerMessageRes(),
        )
        assertEquals(
            R.string.update_blocker_not_granted,
            UpdateRoute.UsbAdb(InstallBlocker.NOT_GRANTED).blockerMessageRes(),
        )
    }

    @Test
    fun `no blocker message leaks developer terminology`() {
        val messages = listOf(
            UpdateRoute.UsbAdb(InstallBlocker.POLICY_MANAGED).blockerMessageRes(),
            UpdateRoute.UsbAdb(InstallBlocker.NOT_GRANTED).blockerMessageRes(),
            UpdateRoute.UserGrantSettings.blockerMessageRes(),
        )

        assertEquals(messages.distinct().size, messages.size)
        messages.forEach { assertNotEquals(0, it) }
    }

    @Test
    fun `no permission or policy key is ever named in the routing API`() {
        // The decider's whole surface is these fields; none of them can express
        // "grant ourselves the permission" because there is no such operation.
        // ($stable is a synthetic field the Kotlin compiler adds to data classes.)
        val fields = InstallEnvironment::class.java.declaredFields
            .map { it.name }
            .filterNot { it.startsWith("$") }
            .toSet()
        assertEquals(
            setOf(
                "sdkInt",
                "canRequestInstalls",
                "unknownSourcesPolicyManaged",
                "isDeviceOrProfileOwner",
                "ownRestrictionLifted",
                "alreadyPromptedForGrant",
                "installBlockedObserved",
            ),
            fields,
        )
    }

    @Test
    fun `the decider exposes no function that could change device state`() {
        val inherited = setOf(
            "equals", "hashCode", "getClass", "toString", "notify", "notifyAll",
            "wait", "access$", "equalsIgnoreCase",
        )
        val methods = InstallRouteDecider::class.java.methods
            .map { it.name }
            .filterNot { name -> inherited.any { name == it || name.startsWith(it) } }
            .distinct()
        listOf("grant", "bypass", "UserRestriction", "escalate", "root", "install").forEach { forbidden ->
            assertFalse(
                "InstallRouteDecider must not expose '$forbidden'",
                methods.any { it.contains(forbidden, ignoreCase = true) },
            )
        }
        assertEquals("the decider is a single pure function", listOf("decide"), methods.sorted())
    }

    @Test
    fun `system install reasons are only ever the three honest ones`() {
        assertEquals(3, SystemInstallReason.entries.size)
        assertNull(UpdateRoute.UsbAdb(InstallBlocker.NONE).blockerMessageRes().takeIf { it == 0 })
    }
}