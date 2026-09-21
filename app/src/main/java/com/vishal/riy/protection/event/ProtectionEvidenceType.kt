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
 * decrypting TLS traffic.
 */
enum class ProtectionEvidenceType {

    /**
     * A DNS query for an adult domain, matched by the filtering VPN's Blocklist
     * before any TLS handshake occurs. This is the one signal RIY can truly
     * observe on Android: visiting any adult site necessarily requires
     * resolving its hostname first, and that lookup passes through the filter.
     */
    ADULT_DOMAIN_DNS_LOOKUP,
}
