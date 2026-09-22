package com.vishal.riy.protection.intelligence

import com.vishal.riy.blocker.BlocklistMatch
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The correlation rules in isolation. Everything here is a pure function of
 * (recorded observations, candidate, clock) — deterministic, no Android, no
 * store, no score.
 */
class EvidenceCorrelatorTest {

    private val correlator = EvidenceCorrelator()

    private val now: Long = 1_000_000_000L

    @Test
    fun `a definitive match is evidence on its own with no corroboration`() {
        val candidate = definitive("one.example", 0)

        val verdict = correlator.evaluate(emptyList(), candidate, now)

        assertTrue(verdict is CorrelationVerdict.EvidenceOnItsOwn)
        assertEquals(candidate, (verdict as CorrelationVerdict.EvidenceOnItsOwn).observation)
    }

    @Test
    fun `one suspect signal alone stays an observation and never becomes evidence`() {
        // The false-positive guarantee in one rule.
        val verdict = correlator.evaluate(emptyList(), suspect("one.example", 0), now)

        assertTrue(verdict is CorrelationVerdict.ObservationOnly)
        assertFalse(verdict is CorrelationVerdict.Corroborated)
    }

    @Test
    fun `a second suspect for a DIFFERENT domain corroborates the first`() {
        val earlier = suspect("one.example", 0)
        val candidate = suspect("two.example", 30_000)

        val verdict = correlator.evaluate(listOf(earlier), candidate, now)

        assertTrue(verdict is CorrelationVerdict.Corroborated)
        assertEquals(
            CorroborationKind.DISTINCT_DOMAIN,
            (verdict as CorrelationVerdict.Corroborated).kind,
        )
    }

    @Test
    fun `the SAME suspect domain repeated past the dedup window corroborates`() {
        val earlier = suspect("one.example", 0)
        val candidate = suspect("one.example", 90_000) // 90s > 60s dedup window

        val verdict = correlator.evaluate(listOf(earlier), candidate, now)

        assertTrue(verdict is CorrelationVerdict.Corroborated)
        assertEquals(
            CorroborationKind.SAME_DOMAIN_REPEAT,
            (verdict as CorrelationVerdict.Corroborated).kind,
        )
    }

    @Test
    fun `the same suspect domain inside the dedup window does NOT corroborate`() {
        // A/AAAA + retries of one host are one observation, not two.
        val earlier = suspect("one.example", 0)
        val candidate = suspect("one.example", 5_000)

        val verdict = correlator.evaluate(listOf(earlier), candidate, now)

        assertTrue(verdict is CorrelationVerdict.ObservationOnly)
    }

    @Test
    fun `an earlier suspect outside the correlation window is ignored`() {
        val tooOld = suspect("one.example", -6 * 60_000 - 1) // 6 min ago, window is 5
        val candidate = suspect("two.example", 0)

        val verdict = correlator.evaluate(listOf(tooOld), candidate, now)

        assertTrue("stale evidence must not corroborate", verdict is CorrelationVerdict.ObservationOnly)
    }

    @Test
    fun `a definitive observation never corroborates a pending suspect`() {
        // A definitive match already produces its own event; letting it also
        // confirm a suspect would double-count one episode.
        val definitive = definitive("one.example", 0)
        val candidate = suspect("two.example", 30_000)

        val verdict = correlator.evaluate(listOf(definitive), candidate, now)

        assertTrue(verdict is CorrelationVerdict.ObservationOnly)
    }

    @Test
    fun `a candidate older than the recorded observations still evaluates correctly`() {
        // Determinism: evaluation is a function of timestamps, not arrival order.
        val later = suspect("two.example", 30_000)
        val candidate = suspect("one.example", 0)

        val verdict = correlator.evaluate(listOf(later), candidate, now)

        assertTrue(verdict is CorrelationVerdict.ObservationOnly)
    }

    @Test
    fun `multiple in-window suspects aggregate to one corroborated verdict`() {
        val first = suspect("one.example", 0)
        val second = suspect("two.example", 20_000)
        val third = suspect("three.example", 40_000)

        // The third completes the pattern; each candidate evaluated against what
        // came before it, so one episode yields exactly one confirmation.
        val verdictForThird = correlator.evaluate(listOf(first, second), third, now)

        assertTrue(verdictForThird is CorrelationVerdict.Corroborated)
        assertEquals(third, (verdictForThird as CorrelationVerdict.Corroborated).observation)
    }

    // ------------------------------------------------------------- helpers

    private fun suspect(domain: String, offsetMillis: Long): SignalObservation =
        SignalObservation(
            domain = domain,
            matchClass = BlocklistMatch.SUSPECT,
            timestamp = now + offsetMillis,
        )

    private fun definitive(domain: String, offsetMillis: Long): SignalObservation =
        SignalObservation(
            domain = domain,
            matchClass = BlocklistMatch.DEFINITIVE,
            timestamp = now + offsetMillis,
        )
}
