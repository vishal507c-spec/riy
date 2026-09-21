package com.vishal.riy.protection.policy

import kotlin.time.Duration
import kotlin.time.DurationUnit
import kotlin.time.toDuration

/**
 * The static, versioned policy configuration. All transition thresholds,
 * durations and escalation rules live here so they exist in exactly one place —
 * never scattered across Activities or services.
 *
 * @param policyVersion      bumped whenever a rule below changes; persisted with
 *                           every session so an inconsistent mix can be detected
 *                           by the integrity engine.
 * @param restrictionDuration the length of a restriction session.
 * @param hardenedThreshold  qualifying detections within [escalationWindow] that
 *                           escalate a session from RESTRICTED to HARDENED.
 * @param escalationWindow   how far back qualifying detections are counted.
 */
data class ProtectionPolicy(

    val policyVersion: Int = CURRENT_POLICY_VERSION,

    val restrictionDuration: Duration = DEFAULT_RESTRICTION_DURATION,

    val hardenedThreshold: Int = DEFAULT_HARDENED_THRESHOLD,

    val escalationWindow: Duration = DEFAULT_ESCALATION_WINDOW,

) {

    companion object {

        const val CURRENT_POLICY_VERSION = 1

        /** Exactly 2 hours, matching the existing LockEngine contract. */
        val DEFAULT_RESTRICTION_DURATION: Duration = 2.toDuration(DurationUnit.HOURS)

        private const val DEFAULT_HARDENED_THRESHOLD = 3

        private val DEFAULT_ESCALATION_WINDOW: Duration = 24.toDuration(DurationUnit.HOURS)
    }
}
