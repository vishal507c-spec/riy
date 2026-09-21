package com.vishal.riy.protection.events

/**
 * A structured, persisted protection event for the recent-events log and
 * long-term diagnostics. Pure Kotlin.
 *
 * @param eventId    stable unique id.
 * @param type       what happened (see [Type]).
 * @param timestamp  epoch ms.
 * @param message    short human-readable detail (shown in the UI log).
 */
data class ProtectionLogEvent(

    val eventId: String,

    val type: Type,

    val timestamp: Long,

    val message: String,

) {

    /** The closed set of event types the protection system records. */
    enum class Type {

        CONTENT_DETECTED,

        PROTECTION_STARTED,

        RESTRICTION_ENTERED,

        APP_BLOCKED,

        INTEGRITY_CHECK,

        INTEGRITY_MISMATCH,

        BOOT_RECOVERY,

        RESTRICTION_EXPIRED,

        POLICY_RECONCILED,
    }
}
