package com.vishal.riy.blocker

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Regression tests for the second half of the bug: the dashboard said
 * "You're Protected" whenever the VPN service had started, so a filter that had
 * loaded nothing, or had never blocked anything, still looked fully protected.
 *
 * These tests pin the evidence model: a status is only ever as strong as the
 * thing that was actually measured.
 */
class ShieldStatusTest {

    private fun snapshot(
        phase: BlockerState.Phase,
        failure: BlockerState.FailureKind = BlockerState.FailureKind.NONE,
        health: BlockerState.FilterHealth = BlockerState.healthFor(phase),
        rules: Int = 60_000,
    ) = BlockerState.Snapshot(
        phase = phase,
        failure = failure,
        health = health,
        ruleCount = rules,
    )

    // ------------------------------------------------- the reported bug

    @Test
    fun `a started VPN service is never reported as verified`() {
        // This is the exact defect: phase CONNECTED used to be enough.
        val status = shieldStatusOf(snapshot(BlockerState.Phase.CONNECTED), protectionWanted = true)
        assertEquals(ShieldStatus.RUNNING_UNVERIFIED, status)
        assertFalse("must not claim verified", status.isVerifiedClaim())
    }

    @Test
    fun `only a passing self test may claim verified`() {
        val verified = shieldStatusOf(
            snapshot(
                phase = BlockerState.Phase.CONNECTED,
                health = BlockerState.FilterHealth.VERIFIED,
            ),
            protectionWanted = true,
        )
        assertEquals(ShieldStatus.VERIFIED, verified)
        assertTrue(verified.isVerifiedClaim())
    }

    // ------------------------------------------------- required states

    @Test
    fun `each required dashboard state is distinguishable`() {
        val wanted = true
        assertEquals(
            ShieldStatus.DISABLED,
            shieldStatusOf(snapshot(BlockerState.Phase.OFF), protectionWanted = false),
        )
        assertEquals(
            "wanted but stopped is DISCONNECTED, not DISABLED",
            ShieldStatus.DISCONNECTED,
            shieldStatusOf(snapshot(BlockerState.Phase.OFF), protectionWanted = wanted),
        )
        assertEquals(
            ShieldStatus.PERMISSION_MISSING,
            shieldStatusOf(
                snapshot(
                    BlockerState.Phase.FAILED,
                    BlockerState.FailureKind.VPN_PERMISSION_MISSING,
                ),
                wanted,
            ),
        )
        assertEquals(
            ShieldStatus.DISCONNECTED,
            shieldStatusOf(
                snapshot(BlockerState.Phase.FAILED, BlockerState.FailureKind.VPN_DISCONNECTED),
                wanted,
            ),
        )
        assertEquals(
            ShieldStatus.INITIALIZING,
            shieldStatusOf(snapshot(BlockerState.Phase.INITIALIZING), wanted),
        )
        assertEquals(
            ShieldStatus.RUNNING_UNVERIFIED,
            shieldStatusOf(
                snapshot(BlockerState.Phase.CONNECTED, health = BlockerState.FilterHealth.RUNNING_UNVERIFIED),
                wanted,
            ),
        )
        assertEquals(
            ShieldStatus.VERIFIED,
            shieldStatusOf(
                snapshot(BlockerState.Phase.CONNECTED, health = BlockerState.FilterHealth.VERIFIED),
                wanted,
            ),
        )
    }

    @Test
    fun `a failed filter is never shown as filtering`() {
        val status = shieldStatusOf(
            snapshot(BlockerState.Phase.CONNECTED, health = BlockerState.FilterHealth.SELF_TEST_FAILED),
            protectionWanted = true,
        )
        assertEquals(ShieldStatus.FILTER_FAILED, status)
        assertFalse(status.isFiltering)
    }

    @Test
    fun `filter init failure is reported as filter failed not disconnected`() {
        val status = shieldStatusOf(
            snapshot(
                BlockerState.Phase.INITIALIZING,
                BlockerState.FailureKind.FILTER_INIT_FAILED,
            ),
            protectionWanted = true,
        )
        assertEquals(ShieldStatus.FILTER_FAILED, status)
    }

    // ------------------------------------------------- encrypted DNS honesty

    @Test
    fun `bypass stays possible unless device policy actually closed it`() {
        val noPolicy = BlockerState.Snapshot(
            phase = BlockerState.Phase.CONNECTED,
            health = BlockerState.FilterHealth.VERIFIED,
        )
        assertTrue("no Device Owner means the route is open", encryptedDnsBypassPossible(noPolicy))

        val restricted = noPolicy.copy(privateDnsRestricted = true)
        assertFalse(encryptedDnsBypassPossible(restricted))
    }

    @Test
    fun `a self test cannot by itself prove bypass is closed`() {
        // Even a perfect self-test says nothing about an app that resolves
        // inside DoH, which the filter cannot read.
        val exposure = EncryptedDnsExposure(
            privateDnsConfigured = false,
            privateDnsRestricted = false,
            selfTestVerified = true,
        )
        assertTrue(exposure.bypassPossible)
        assertTrue(exposure.unverified)
    }
}

/** The one claim the UI is allowed to make about verification. */
private fun ShieldStatus.isVerifiedClaim(): Boolean = this == ShieldStatus.VERIFIED