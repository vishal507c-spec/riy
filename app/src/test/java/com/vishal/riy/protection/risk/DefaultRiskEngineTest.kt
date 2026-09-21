package com.vishal.riy.protection.risk

import com.vishal.riy.protection.adultDomainLookupEvent
import com.vishal.riy.protection.event.ProtectionEvent
import com.vishal.riy.protection.event.ProtectionEventSource
import com.vishal.riy.protection.event.ProtectionEvidenceType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The production risk engine — the smallest honest grading of the one signal
 * RIY can genuinely observe. Pure JVM: no Android, no device.
 */
class DefaultRiskEngineTest {

    private val engine = DefaultRiskEngine()

    @Test
    fun `an adult domain DNS lookup is graded as confirmed evidence`() {
        val event = adultDomainLookupEvent(DOMAIN, NOW)

        val assessment = engine.assess(event)

        assertEquals(RiskLevel.CONFIRMED, assessment.riskLevel)
        assertTrue("a blocklist match is actionable evidence", assessment.isActionable)
    }

    @Test
    fun `the assessment carries the event's own confidence`() {
        val event = adultDomainLookupEvent(DOMAIN, NOW)

        val assessment = engine.assess(event)

        assertEquals(ProtectionEvent.Confidence.CERTAIN, assessment.confidence)
    }

    @Test
    fun `the assessment is deterministic for the same event`() {
        val event = adultDomainLookupEvent(DOMAIN, NOW)

        val first = engine.assess(event)
        val second = engine.assess(event)

        assertEquals(first, second)
        assertSame("no defensive copy of the evidence is made", event, first.event)
    }

    @Test
    fun `the evidence type is carried explicitly so a consumer never re-derives it`() {
        val event = adultDomainLookupEvent(DOMAIN, NOW)

        val assessment = engine.assess(event)

        assertEquals(ProtectionEvidenceType.ADULT_DOMAIN_DNS_LOOKUP, assessment.evidenceType)
    }

    @Test
    fun `the reasoning is present, stable and leaks no private detail`() {
        val assessment = engine.assess(adultDomainLookupEvent(DOMAIN, NOW))

        assertNotNull(assessment.reasoning)
        assertTrue(assessment.reasoning.isNotBlank())
        assertTrue(
            "the matched domain must not be duplicated into the reasoning",
            DOMAIN !in assessment.reasoning,
        )
        // A second event of the same kind yields the same explanation.
        assertEquals(
            assessment.reasoning,
            engine.assess(adultDomainLookupEvent("other.$DOMAIN", NOW + 1)).reasoning,
        )
    }

    @Test
    fun `the event keeps its source and metadata intact`() {
        val event = adultDomainLookupEvent(DOMAIN, NOW)

        val assessment = engine.assess(event)

        assertEquals(ProtectionEventSource.DNS_FILTER, assessment.event.source)
        assertEquals(DOMAIN, assessment.event.metadata[ProtectionEvent.META_DOMAIN])
    }

    private companion object {
        const val NOW = 1_000_000L
        const val DOMAIN = "example.invalid"
    }
}
