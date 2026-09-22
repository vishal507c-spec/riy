package com.vishal.riy.protection.event

/**
 * The kinds of evidence that can legitimately arm the RIY protection system.
 *
 * ARCHITECTURE RULE: this enum is the ONLY place a detection source can enter
 * the protection pipeline. A caller cannot fabricate a source, because
 * [ProtectionEvent.evidenceType] is a closed enum and every consumer (risk
 * engine, policy engine, event store) must handle it exhaustively. Adding a
 * new genuine signal is therefore a deliberate, reviewed change to this file —
 * an accidental or fake source (browser keyword, image scan, …) has no way in.
 *
 * Currently exactly one source is genuinely observable by RIY without
 * decrypting TLS traffic, plus the one tier the Phase 10 intelligence layer
 * derives from it by deterministic correlation.
 */
enum class ProtectionEvidenceType {

    /**
     * A DNS query for an adult domain, matched by the filtering VPN's Blocklist
     * before any TLS handshake occurs. This is the one signal RIY can truly
     * observe on Android: visiting any adult site necessarily requires
     * resolving its hostname first, and that lookup passes through the filter.
     */
    ADULT_DOMAIN_DNS_LOOKUP,

    /**
     * A whole-label token match (for example a registrable `adult` / `nude` /
     * `nsfw` label) that was observed at least twice — by two different hosts,
     * or by the same host well after the DNS dedup window — inside the
     * detection window. It is the ambiguous class of blocklist match, so ONE
     * such observation is deliberately NOT enough; only the corroborated pair
     * becomes evidence, and it enters the pipeline through the same event
     * contract every other signal uses.
     *
     * Corroboration is decided by
     * [com.vishal.riy.protection.intelligence.EvidenceCorrelator]; this enum
     * only says the evidence exists.
     */
    CORROBORATED_ADULT_CONTENT,
    ;

    /**
     * True when this evidence type is an adult-domain observation of any
     * strength — i.e. when it legitimately counts toward the existing
     * escalation counting. Centralised so the escalation engine has exactly one
     * place to ask, and so adding a genuinely unrelated signal later cannot
     * accidentally start counting.
     */
    val isAdultDomainEvidence: Boolean
        get() = this == ADULT_DOMAIN_DNS_LOOKUP ||
            this == CORROBORATED_ADULT_CONTENT
}
