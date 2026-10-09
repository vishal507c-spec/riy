package com.vishal.riy.blocker

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The self-test verdict logic, isolated from the sockets.
 *
 * The point of these tests is that a verdict can only be VERIFIED when every
 * single measurement supports it — including the rule count, because an empty
 * blocklist "blocks everything" and "blocks nothing" look identical from the
 * outside.
 */
class SelfTestReportTest {

    private val plenty = 60_000

    @Test
    fun `blocked sinkholed and normal site reachable is verified`() {
        val report = SelfTestReport.evaluate(plenty, blockSinkholed = true, allowResolved = true)
        assertEquals(SelfTestOutcome.VERIFIED, report.outcome)
        assertTrue(report.verified)
        assertEquals(BlockerState.FilterHealth.VERIFIED, report.health())
    }

    @Test
    fun `a reachable adult domain is a failure not a pass`() {
        val report = SelfTestReport.evaluate(plenty, blockSinkholed = false, allowResolved = true)
        assertEquals(SelfTestOutcome.BLOCK_NOT_ENFORCED, report.outcome)
        assertFalse(report.verified)
        assertEquals(BlockerState.FilterHealth.SELF_TEST_FAILED, report.health())
    }

    @Test
    fun `blackholing ordinary browsing is a failure`() {
        val report = SelfTestReport.evaluate(plenty, blockSinkholed = true, allowResolved = false)
        assertEquals(SelfTestOutcome.ALLOW_PATH_BROKEN, report.outcome)
        assertFalse(report.verified)
    }

    @Test
    fun `a near empty blocklist can never be verified`() {
        val report = SelfTestReport.evaluate(0, blockSinkholed = true, allowResolved = true)
        assertEquals(SelfTestOutcome.NO_RULES_LOADED, report.outcome)
        assertFalse(report.verified)
    }

    @Test
    fun `no answer downgrades to unverified instead of claiming success`() {
        val report = SelfTestReport.evaluate(plenty, blockSinkholed = null, allowResolved = null)
        assertEquals(SelfTestOutcome.NO_RESPONSE, report.outcome)
        // Not a failure — we simply do not know yet, so we must not claim green.
        assertEquals(BlockerState.FilterHealth.RUNNING_UNVERIFIED, report.health())
        assertFalse(report.verified)
    }

    @Test
    fun `summaries never claim more than was measured`() {
        val passing = SelfTestReport.evaluate(plenty, true, true)
        assertTrue(passing.summary().contains("Verified"))

        val unverified = SelfTestReport.evaluate(plenty, null, null)
        assertFalse(
            "an unanswered probe must not read as verified",
            unverified.summary().contains("Verified"),
        )
    }

    @Test
    fun `the canaries are on opposite sides of the filter`() {
        // The block canary must be an adult name the matcher resolves locally,
        // so the self-test never contacts a real adult site.
        assertTrue(SelfTestCanaries.BLOCK.endsWith(".xxx"))
        assertEquals("example.com", SelfTestCanaries.ALLOW)
        assertNull(
            "example.com must never match an adult rule",
            Blocklist(emptyList()).classify(SelfTestCanaries.ALLOW),
        )
    }

    @Test
    fun `the minimum rule threshold is far below the shipped list size`() {
        // Guards against the threshold being raised into the real asset size,
        // which would make the self-test permanently unpassable.
        assertTrue(SelfTestReport.MIN_PLAUSIBLE_RULES < 5_000)
        assertTrue(SelfTestReport.MIN_PLAUSIBLE_RULES > 100)
    }

    @Test
    fun `the self test probe is exempt from detection but still blocked`() {
        // Regression: the canary must be blocked (that is the proof), yet must
        // never be reported as an adult-domain DETECTION, or the app would arm a
        // protection event — and eventually a lock — from its own health check.
        val list = Blocklist(emptyList())
        assertTrue(
            "canary must still be blocked",
            list.contains(SelfTestCanaries.BLOCK),
        )
        assertTrue(SelfTestCanaries.isSelfTestQuery(SelfTestCanaries.BLOCK))
        assertTrue(
            "normalization must not let a variant slip past the exemption",
            SelfTestCanaries.isSelfTestQuery("  ${SelfTestCanaries.BLOCK.uppercase()}.  "),
        )
        assertFalse(
            "a real adult domain is never exempt",
            SelfTestCanaries.isSelfTestQuery("pornhub.com"),
        )
        assertFalse(SelfTestCanaries.isSelfTestQuery(null))
    }
}