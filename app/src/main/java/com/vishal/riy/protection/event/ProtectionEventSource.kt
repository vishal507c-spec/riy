package com.vishal.riy.protection.event

/**
 * The subsystem that produced a [ProtectionEvent]. Closed for the same reason
 * as [ProtectionEvidenceType]: only real, existing RIY components may emit
 * protection events.
 */
enum class ProtectionEventSource {

    /** The DNS-filtering VPN service (the adult-domain detection point). */
    DNS_FILTER,
}
