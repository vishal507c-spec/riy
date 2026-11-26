package com.vishal.riy.ui

import com.vishal.riy.blocker.ShieldStatus
import org.junit.Assert.assertEquals
import org.junit.Test

/** The dashboard shows exactly four truthful states. */
class ShieldDisplayTest {

    @Test
    fun `only a passing self test shows PROTECTED`() {
        ShieldStatus.values().forEach { status ->
            val expected = when (status) {
                ShieldStatus.VERIFIED -> ShieldDisplayState.PROTECTED
                ShieldStatus.RUNNING_UNVERIFIED,
                ShieldStatus.FILTER_FAILED,
                -> ShieldDisplayState.NOT_VERIFIED
                ShieldStatus.INITIALIZING,
                ShieldStatus.UNKNOWN,
                -> ShieldDisplayState.CHECKING
                else -> ShieldDisplayState.NOT_PROTECTED
            }
            assertEquals("$status", expected, shieldDisplayOf(status))
        }
    }

    @Test
    fun `a busy action never upgrades a weak backend state`() {
        assertEquals(
            ShieldDisplayState.CHECKING,
            shieldDisplayOf(ShieldStatus.RUNNING_UNVERIFIED, busy = true),
        )
        assertEquals(
            ShieldDisplayState.CHECKING,
            shieldDisplayOf(ShieldStatus.DISCONNECTED, busy = true),
        )
    }
}
