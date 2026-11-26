package com.vishal.riy.blocker

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** A failed verification must never erase the last PASSING verification time. */
class BlockerStateVerificationTest {

    @Test
    fun `verification timestamp survives later failures and transitions`() {
        // Isolate the singleton from other tests.
        BlockerState.update(BlockerState.Phase.OFF)
        assertNull(BlockerState.current().lastVerifiedAt)

        BlockerState.update(
            phase = BlockerState.Phase.CONNECTED,
            health = BlockerState.FilterHealth.VERIFIED,
            lastVerifiedAt = 1_700_000_000_000L,
        )
        val verified = BlockerState.current()
        assertEquals(BlockerState.FilterHealth.VERIFIED, verified.health)
        assertEquals(1_700_000_000_000L, verified.lastVerifiedAt)

        BlockerState.update(
            phase = BlockerState.Phase.CONNECTED,
            health = BlockerState.FilterHealth.RUNNING_UNVERIFIED,
        )
        assertEquals(1_700_000_000_000L, BlockerState.current().lastVerifiedAt)

        BlockerState.update(
            phase = BlockerState.Phase.FAILED,
            failureReason = "filter stopped",
        )
        assertEquals(1_700_000_000_000L, BlockerState.current().lastVerifiedAt)

        // Return to a clean state for any later test.
        BlockerState.update(BlockerState.Phase.OFF)
    }
}
