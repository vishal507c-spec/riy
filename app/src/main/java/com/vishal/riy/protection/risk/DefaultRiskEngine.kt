package com.vishal.riy.protection.risk

import com.vishal.riy.protection.event.ProtectionEvent
import com.vishal.riy.protection.event.ProtectionEvidenceType

/**
 * THE production implementation of [RiskEngine], and the smallest one that is
 * honest about what RIY can actually observe.
 *
 * It grades exactly the signals the filtering VPN can actually observe — a DNS
 * query for an adult domain that the blocklist matched before any TLS
 * handshake — at either of the two strengths that match can have. HTTPS
 * keywords, image content, notifications and screen state are NOT visible to a
 * non-MITM Android app, so this engine never pretends to grade them: there is
 * no heuristic branch, no keyword branch and no arbitrary score.
 *
 * THE CORROBORATION RULE IS NOT A SCORE. A
 * [com.vishal.riy.protection.event.ProtectionEvidenceType.CORROBORATED_ADULT_CONTENT]
 * event reaches this engine ONLY after
 * [com.vishal.riy.protection.intelligence.EvidenceCorrelator] found a second,
 * independent observation. This engine does not re-weigh that decision and it
 * does not discount it: corroboration is a deterministic yes/no that already
 * happened upstream, so the assessment below stays a pure function of the event.
 *
 * HARD CONTRACT, unchanged from [RiskEngine]: pure Kotlin, deterministic given
 * the same event, no Android API, no storage, no state transition. This class
 * grades evidence; it decides nothing.
 *
 * EXHAUSTIVENESS: [assess] matches [ProtectionEvidenceType] without an `else`
 * arm on purpose. The enum is the only door into the pipeline, and the day a
 * second genuine signal is added the compiler refuses to build this `when`
 * until it is graded here as well — a fabricated source has no way in.
 */
class DefaultRiskEngine : RiskEngine {

    override fun assess(event: ProtectionEvent): RiskAssessment = when (event.evidenceType) {

        /**
         * A blocklist-matched adult-domain lookup. The domain itself is already
         * authoritative evidence: it was matched by the production [com.vishal.riy.blocker.Blocklist]
         * (explicit rule, adult TLD or whole-label token) before this event was
         * ever created, so no second-guessing and no confidence discounting is
         * applied here. That makes the assessment deterministic — the same
         * event always yields the same severity.
         *
         * [RiskLevel.CONFIRMED] is the severity of ONE such observation; the
         * existing policy engine maps it onto the 2-hour RESTRICTED session.
         */
        ProtectionEvidenceType.ADULT_DOMAIN_DNS_LOOKUP -> RiskAssessment(
            riskLevel = RiskLevel.CONFIRMED,
            confidence = event.confidence,
            evidenceType = event.evidenceType,
            reasoning = REASON_ADULT_DOMAIN_DNS_LOOKUP,
            event = event,
        )

        /**
         * A token-class match that was independently corroborated inside the
         * detection window. The corroboration already happened upstream — this
         * arm treats the event as the confirmed observation it now is, with no
         * further discounting, so a corroborated signal and a direct blocklist
         * match reach the policy engine at the same severity. That is what
         * keeps one weak observation from being both necessary and insufficient
         * at the same time.
         */
        ProtectionEvidenceType.CORROBORATED_ADULT_CONTENT -> RiskAssessment(
            riskLevel = RiskLevel.CONFIRMED,
            confidence = event.confidence,
            evidenceType = event.evidenceType,
            reasoning = REASON_CORROBORATED_ADULT_CONTENT,
            event = event,
        )
    }

    private companion object {

        /**
         * Fixed, evidence-derived wording. It deliberately repeats no domain and
         * no other potentially private fact — the matched domain already travels
         * in [ProtectionEvent.metadata] under the event contract's own key.
         */
        const val REASON_ADULT_DOMAIN_DNS_LOOKUP =
            "adult-domain DNS lookup matched the blocklist"

        const val REASON_CORROBORATED_ADULT_CONTENT =
            "ambiguous adult-domain signal corroborated by an independent observation"
    }
}
