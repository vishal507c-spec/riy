package com.vishal.riy.protection.risk

/**
 * Severity assigned by the risk engine to a single [com.vishal.riy.protection.event.ProtectionEvent].
 *
 * This describes ONE observation, not the persistent protection state (that is
 * [com.vishal.riy.protection.policy.ProtectionState]). The policy engine maps
 * risk levels onto protection states; the two are deliberately separate so a
 * single detection can never directly arm a restriction.
 */
enum class RiskLevel {

    /** No actionable signal. */
    NORMAL,

    /** Weak/ambiguous evidence — recorded, but not sufficient to act on. */
    SUSPICIOUS,

    /** A genuine prohibited-content observation (for example a matched adult domain). */
    CONFIRMED,

    /** A confirmed observation that qualifies the device for the 2-hour restriction. */
    RESTRICTED,

    /** Repeated qualifying observations; the policy engine may escalate further. */
    HARDENED,
}
