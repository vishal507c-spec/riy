package com.vishal.riy.protection.event

/**
 * Immutable, pure-Kotlin (no Android imports) record of one thing the
 * protection system observed. This is the input to the whole pipeline:
 *
 *     Detection → Risk → Policy → State → Enforcement
 *
 * A [ProtectionEvent] carries no authority on its own: it cannot change the
 * protection state, start a restriction or touch DevicePolicyManager. It is
 * only evidence; every consequence is decided by the policy engine.
 *
 * @param eventId      stable unique id (a UUID in production).
 * @param timestamp    epoch milliseconds when the observation happened.
 * @param source       the RIY subsystem that observed it.
 * @param evidenceType what kind of evidence it is; must correspond to a real,
 *                     currently-supported detection signal.
 * @param confidence   how strongly the evidence indicates prohibited content,
 *                     in the closed range 0.0 – 1.0.
 * @param metadata     extra key/value facts about the observation (for example
 *                     the matched domain). Never used to make the decision
 *                     alone — it is diagnostic context only.
 */
data class ProtectionEvent(
    val eventId: String,
    val timestamp: Long,
    val source: ProtectionEventSource,
    val evidenceType: ProtectionEvidenceType,
    val confidence: Confidence,
    val metadata: Map<String, String> = emptyMap(),
) {

    /**
     * Type-safe confidence in [0.0, 1.0]. Constructed via [of] so an out-of-range
     * value can never silently reach the risk engine.
     */
    @JvmInline
    value class Confidence private constructor(val value: Double) {

        companion object {

            /** Clamps [value] into the valid range; 0 means "no signal". */
            fun of(value: Double): Confidence = Confidence(value.coerceIn(0.0, 1.0))

            /** Convenience for a fully certain observation. */
            val CERTAIN: Confidence = Confidence(1.0)

            /** Convenience for an observation carrying no signal. */
            val NONE: Confidence = Confidence(0.0)
        }
    }

    companion object {

        /** Metadata key holding the matched domain for [ProtectionEvidenceType.ADULT_DOMAIN_DNS_LOOKUP]. */
        const val META_DOMAIN = "domain"
    }
}
