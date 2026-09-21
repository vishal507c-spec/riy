package com.vishal.riy.protection.policy

/**
 * The persistent protection state of the device.
 *
 * NOTE ON AUTHORITY: this enum describes what the device is currently under.
 * Transitions between these values may ONLY be decided by
 * [ProtectionPolicyEngine] — never by the UI, never by the enforcement layer,
 * never by an Activity. The UI renders this value; it cannot set it.
 *
 * Distinct from [com.vishal.riy.protection.risk.RiskLevel], which grades a
 * single event.
 */
enum class ProtectionState {

    /** No protection session is active. */
    NORMAL,

    /** Weak evidence seen; monitoring only, nothing is restricted. */
    SUSPICIOUS,

    /** Prohibited content confirmed; the session may be armed shortly. */
    CONFIRMED,

    /** The 2-hour restriction session is live. */
    RESTRICTED,

    /** Escalated restriction after repeated qualifying events. */
    HARDENED,

    /** The system is restoring a persisted session after a reboot/restart. */
    RECOVERY,
}
