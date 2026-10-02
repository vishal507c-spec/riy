package com.vishal.riy.ui

import com.vishal.riy.protection.enforcement.AllowedApp
import com.vishal.riy.protection.enforcement.AllowedAppCategory
import com.vishal.riy.protection.enforcement.EnforcementStatus
import com.vishal.riy.protection.policy.ProtectionState
import com.vishal.riy.protection.ui.ProtectionUiState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The feature showcase mapping: WHAT the app guards must follow the real
 * backend reads, and the pipeline must stay four static steps. Pure JVM —
 * [shieldLayers] and [howItWorksSteps] hold no Compose state.
 */
class ShieldLayersTest {

    private fun layer(state: ProtectionUiState, id: ShieldLayerId): ShieldLayer =
        shieldLayers(state).first { it.id == id }

    @Test
    fun `showcase always lists the six guard layers exactly once`() {
        val layers = shieldLayers(idle())

        assertEquals(6, layers.size)
        assertEquals(ShieldLayerId.entries.toSet(), layers.map { it.id }.toSet())
        assertEquals(6, layers.map { it.titleRes }.toSet().size)
        assertEquals("every layer reveals a second-depth line", 6, layers.map { it.moreRes }.toSet().size)
    }

    @Test
    fun `network layer is active only while the filter really runs`() {
        assertEquals(
            ShieldLayerStatus.ACTIVE,
            layer(idle().copy(protectionActive = true), ShieldLayerId.NETWORK).status,
        )
        assertEquals(
            ShieldLayerStatus.UNAVAILABLE,
            layer(idle(), ShieldLayerId.NETWORK).status,
        )
    }

    @Test
    fun `limit layer is active on restriction, ready while armed, else unavailable`() {
        assertEquals(ShieldLayerStatus.ACTIVE, layer(restricted(), ShieldLayerId.LOCK).status)
        assertEquals(
            ShieldLayerStatus.READY,
            layer(idle().copy(protectionActive = true), ShieldLayerId.LOCK).status,
        )
        assertEquals(ShieldLayerStatus.UNAVAILABLE, layer(idle(), ShieldLayerId.LOCK).status)
    }

    @Test
    fun `telegram layer is active when unrestricted and when confirmed in the allowlist`() {
        assertEquals(ShieldLayerStatus.ACTIVE, layer(idle(), ShieldLayerId.TELEGRAM).status)
        assertEquals(
            ShieldLayerStatus.ACTIVE,
            layer(restrictedWithTelegram(), ShieldLayerId.TELEGRAM).status,
        )
    }

    @Test
    fun `telegram layer is ready — never unavailable — when restricted without the app installed`() {
        // Policy never blocks Telegram; without the app on the device there is
        // simply nothing to confirm, so the honest verdict is READY.
        assertEquals(ShieldLayerStatus.READY, layer(restricted(), ShieldLayerId.TELEGRAM).status)
    }

    @Test
    fun `bypass and install guards follow verified enforcement, else owner, else unavailable`() {
        listOf(ShieldLayerId.BYPASS, ShieldLayerId.INSTALL).forEach { id ->
            assertEquals(ShieldLayerStatus.ACTIVE, layer(restricted(), id).status)
            assertEquals(
                ShieldLayerStatus.READY,
                layer(idle().copy(deviceOwnerActive = true), id).status,
            )
            assertEquals(ShieldLayerStatus.UNAVAILABLE, layer(idle(), id).status)
        }
    }

    @Test
    fun `recovery layer is active only when integrity really verified`() {
        assertEquals(
            ShieldLayerStatus.ACTIVE,
            layer(idle().copy(integrityVerified = true), ShieldLayerId.RECOVERY).status,
        )
        assertEquals(ShieldLayerStatus.UNAVAILABLE, layer(idle(), ShieldLayerId.RECOVERY).status)
    }

    @Test
    fun `pipeline is four numbered steps with distinct titles`() {
        val steps = howItWorksSteps()

        assertEquals(4, steps.size)
        assertEquals(listOf(1, 2, 3, 4), steps.map { it.number })
        assertEquals(4, steps.map { it.titleRes }.toSet().size)
    }

    // ------------------------------------------------------------- helpers

    private fun idle() = ProtectionUiState(
        protectionState = ProtectionState.NORMAL,
        protectionActive = false,
        deviceOwnerActive = false,
        uninstallProtectionActive = false,
        integrityVerified = false,
        enforcementStatus = EnforcementStatus.NORMAL,
    )

    private fun restricted() = ProtectionUiState(
        protectionState = ProtectionState.RESTRICTED,
        protectionActive = true,
        remainingTime = 6_120_000L,
        deviceOwnerActive = true,
        uninstallProtectionActive = true,
        integrityVerified = true,
        enforcementStatus = EnforcementStatus.ACTIVE_RESTRICTED,
    )

    private fun restrictedWithTelegram() = restricted().copy(
        allowedApps = listOf(
            AllowedApp("org.telegram.messenger", "Telegram", AllowedAppCategory.COMMUNICATION),
        ),
    )
}
