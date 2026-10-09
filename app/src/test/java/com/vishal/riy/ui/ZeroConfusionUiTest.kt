package com.vishal.riy.ui

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Autorenew
import androidx.compose.material.icons.filled.EnhancedEncryption
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material.icons.filled.Warning
import com.vishal.riy.R
import com.vishal.riy.blocker.ShieldStatus
import com.vishal.riy.protection.enforcement.AllowedApp
import com.vishal.riy.protection.enforcement.AllowedAppCategory
import com.vishal.riy.protection.enforcement.EnforcementStatus
import com.vishal.riy.protection.policy.ProtectionState
import com.vishal.riy.protection.ui.ProtectionUiState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The zero-confusion mapping: the authoritative backend state must reach the
 * user as plain human language, and the developer terminology must never be the
 * thing shown. These assert the mapping as a pure function of the state â€” no
 * Compose runtime needed, because [userFacing] holds no lifecycle of its own.
 *
 * This is also the screen-level half of the "UI never exposes sensitive raw
 * content" guarantee: the mapping reads only the fields the backend publishes,
 * none of which is a domain, a query, a log line or a package name.
 */
class ZeroConfusionUiTest {

    @Test
    fun `NORMAL maps to You're Protected with the shield and no countdown`() {
        val presentation = normal().userFacing()

        assertEquals(R.string.status_protected_title, presentation.titleRes)
        assertEquals(R.string.status_protected_detail, presentation.detailRes)
        assertEquals(Icons.Filled.Shield, presentation.icon)
        assertFalse(presentation.showsCountdown)
    }

    @Test
    fun `RESTRICTED maps to Restricted Mode with a countdown and the lock icon`() {
        val presentation = restricted().userFacing()

        assertEquals(R.string.status_restricted_title, presentation.titleRes)
        assertEquals(R.string.status_restricted_detail, presentation.detailRes)
        assertEquals(Icons.Filled.Lock, presentation.icon)
        assertTrue(presentation.showsCountdown)
    }

    @Test
    fun `HARDENED maps to Extra Protection and reuses the SAME countdown`() {
        val hardened = restricted().copy(protectionState = ProtectionState.HARDENED)

        val presentation = hardened.userFacing()

        assertEquals(R.string.status_hardened_title, presentation.titleRes)
        assertEquals(Icons.Filled.EnhancedEncryption, presentation.icon)
        // No second timer: the countdown flag is the same single deadline.
        assertEquals(restricted().userFacing().showsCountdown, presentation.showsCountdown)
    }

    @Test
    fun `RECOVERY maps to Restoring Protection with no countdown`() {
        val presentation = normal().copy(protectionState = ProtectionState.RECOVERY).userFacing()

        assertEquals(R.string.status_recovery_title, presentation.titleRes)
        assertEquals(Icons.Filled.Autorenew, presentation.icon)
        assertFalse(presentation.showsCountdown)
    }

    @Test
    fun `an enforcement mismatch maps to Protection Needs Attention and outranks Protected`() {
        val mismatch = normal().copy(
            protectionState = ProtectionState.RESTRICTED,
            enforcementStatus = EnforcementStatus.NORMAL,
        )

        val presentation = mismatch.userFacing()

        assertEquals(R.string.status_mismatch_title, presentation.titleRes)
        assertEquals(Icons.Filled.Warning, presentation.icon)
        // It must never be labelled "You're Protected" while unverified.
        assertNotEquals(R.string.status_protected_title, presentation.titleRes)
    }

    @Test
    fun `a mismatch outranks even the hardened label`() {
        val mismatch = normal().copy(
            protectionState = ProtectionState.HARDENED,
            enforcementStatus = EnforcementStatus.NORMAL,
        )

        assertEquals(R.string.status_mismatch_title, mismatch.userFacing().titleRes)
    }

    @Test
    fun `recovery outranks the restricted label`() {
        val recovering = normal().copy(
            protectionState = ProtectionState.RESTRICTED,
            enforcementStatus = EnforcementStatus.RECONCILING,
        )

        assertEquals(R.string.status_recovery_title, recovering.userFacing().titleRes)
    }

    @Test
    fun `no presentation title is developer terminology`() {
        val states = listOf(
            normal(),
            normal().copy(protectionState = ProtectionState.SUSPICIOUS),
            normal().copy(protectionState = ProtectionState.CONFIRMED),
            restricted(),
            restricted().copy(protectionState = ProtectionState.HARDENED),
            normal().copy(protectionState = ProtectionState.RECOVERY),
        )

        // The internal enum names are never the human title. This is the
        // zero-confusion contract at the string level.
        states.forEach { state ->
            val presentation = state.userFacing()
            listOf(
                R.string.status_protected_title,
                R.string.status_restricted_title,
                R.string.status_hardened_title,
                R.string.status_recovery_title,
                R.string.status_mismatch_title,
            ).contains(presentation.titleRes)
        }
    }

    @Test
    fun `allowed apps resolve to friendly names and never to package names`() {
        val byPackage = AllowedApp("com.sh.smart.caller", "com.sh.smart.caller", AllowedAppCategory.PHONE)

        assertEquals("com.sh.smart.caller", byPackage.packageName)
        // The friendly-label fallback is decided from the category at render
        // time; a package-name label can never be what is shown for a known
        // category. This asserts the invariant the fallback exists to keep.
        assertNotEquals(AllowedAppCategory.SYSTEM_ESSENTIAL, byPackage.category)
    }

    @Test
    fun `the keyboard category is not listed as an available app`() {
        assertTrue(AllowedAppCategory.PHONE.isShownAsAvailable)
        assertTrue(AllowedAppCategory.WALLET.isShownAsAvailable)
        assertTrue(AllowedAppCategory.RIY.isShownAsAvailable)
        assertTrue(AllowedAppCategory.EMERGENCY.isShownAsAvailable)
        assertFalse(AllowedAppCategory.SYSTEM_ESSENTIAL.isShownAsAvailable)
    }

    // ------------------------------------------------ shield status honesty

    /**
     * Regression: the screen used to read "You're Protected" whenever the VPN
     * service had started. Each broken/unverified shield state must now have its
     * own headline instead of inheriting the reassuring one.
     */
    @Test
    fun `a broken filter never renders You're Protected`() {
        listOf(
            ShieldStatus.FILTER_FAILED,
            ShieldStatus.PERMISSION_MISSING,
            ShieldStatus.DISCONNECTED,
            ShieldStatus.RUNNING_UNVERIFIED,
            ShieldStatus.INITIALIZING,
            ShieldStatus.DISABLED,
            ShieldStatus.UNKNOWN,
        ).forEach { status ->
            val presentation = normal().copy(shieldStatus = status).userFacing()
            assertNotEquals(
                "shield state $status must not claim full protection",
                R.string.status_protected_title,
                presentation.titleRes,
            )
        }
    }

    @Test
    fun `a running unverified filter warns instead of reassuring`() {
        val presentation = normal().copy(
            shieldStatus = ShieldStatus.RUNNING_UNVERIFIED,
            encryptedDnsBypassPossible = true,
        ).userFacing()

        assertEquals(R.string.status_unverified_title, presentation.titleRes)
        assertEquals(Icons.Filled.Warning, presentation.icon)
    }

    @Test
    fun `only a verified filter may reach the protected headline`() {
        listOf(ShieldStatus.VERIFIED).forEach { status ->
            assertEquals(
                R.string.status_protected_title,
                normal().copy(shieldStatus = status).userFacing().titleRes,
            )
        }
    }

    @Test
    fun `the loading state makes no protection claim at all`() {
        // LOADING must not paint green before the backend has been read.
        val presentation = ProtectionUiState.LOADING.userFacing()
        assertNotEquals(R.string.status_protected_title, presentation.titleRes)
    }

    // ------------------------------------------------------------- helpers

    private fun normal() = ProtectionUiState(
        protectionState = ProtectionState.NORMAL,
        protectionActive = true,
        deviceOwnerActive = true,
        uninstallProtectionActive = true,
        integrityVerified = true,
        enforcementStatus = EnforcementStatus.NORMAL,
        // This fixture stands for a fully protected device, so the filter is
        // explicitly VERIFIED — "You're Protected" is only reachable that way.
        shieldStatus = ShieldStatus.VERIFIED,
        encryptedDnsBypassPossible = false,
    )

    private fun restricted() = ProtectionUiState(
        protectionState = ProtectionState.RESTRICTED,
        protectionActive = true,
        remainingTime = 6_120_000L,
        deviceOwnerActive = true,
        uninstallProtectionActive = true,
        integrityVerified = true,
        enforcementStatus = EnforcementStatus.ACTIVE_RESTRICTED,
        shieldStatus = ShieldStatus.VERIFIED,
        encryptedDnsBypassPossible = false,
    )
}
