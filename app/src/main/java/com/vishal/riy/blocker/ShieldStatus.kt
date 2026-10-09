package com.vishal.riy.blocker

/**
 * THE dashboard status for the network filter, derived as a PURE function of
 * [BlockerState.Snapshot].
 *
 * The rule this file exists to enforce: the app must never show "protected"
 * merely because the VPN service started. Every value below is backed by
 * something that actually happened — a phase, a loaded rule count, a self-test
 * result — so the honest and the reassuring states cannot be confused.
 *
 * Deliberate honesty: there is no "fully protected" value at all. A DNS filter
 * can be [SelfTestReport.VERIFIED] (it demonstrably blocks a canary adult
 * domain end to end) while an application with its own encrypted DNS still
 * reaches the network without being inspectable. [RUNNING_UNVERIFIED] is what
 * the UI shows then, not a green tick.
 */
enum class ShieldStatus {

    /** The backend has not been read yet; nothing may be claimed. */
    UNKNOWN,

    /** Protection is switched off (or was never enabled). */
    DISABLED,

    /** VPN consent is missing/revoked, or another VPN owns the slot. */
    PERMISSION_MISSING,

    /** Protection is wanted but the tunnel is not up. */
    DISCONNECTED,

    /** The tunnel is up; the blocklist is still loading, so nothing blocks yet. */
    INITIALIZING,

    /** The filter itself failed to start (blocklist unreadable, and friends). */
    FILTER_FAILED,

    /** The filter is running but protection could NOT be verified. */
    RUNNING_UNVERIFIED,

    /** The self-test proved adult domains are blocked and normal sites load. */
    VERIFIED;

    /** True only when the app may state that filtering is actually working. */
    val isFiltering: Boolean
        get() = this == RUNNING_UNVERIFIED || this == VERIFIED
}

/**
 * Derives the status. [protectionWanted] is the user's persisted ON/OFF choice,
 * which is what lets "switched off" be distinguished from "switched on but
 * broken" — two states that look identical from [BlockerState.Phase] alone.
 */
fun shieldStatusOf(
    snapshot: BlockerState.Snapshot,
    protectionWanted: Boolean,
): ShieldStatus = when (snapshot.phase) {

    BlockerState.Phase.OFF ->
        if (protectionWanted) ShieldStatus.DISCONNECTED else ShieldStatus.DISABLED

    BlockerState.Phase.CONNECTING ->
        ShieldStatus.DISCONNECTED

    BlockerState.Phase.INITIALIZING ->
        if (snapshot.failure == BlockerState.FailureKind.FILTER_INIT_FAILED) {
            ShieldStatus.FILTER_FAILED
        } else {
            ShieldStatus.INITIALIZING
        }

    BlockerState.Phase.CONNECTED ->
        when (snapshot.health) {
            BlockerState.FilterHealth.VERIFIED -> ShieldStatus.VERIFIED
            BlockerState.FilterHealth.SELF_TEST_FAILED -> ShieldStatus.FILTER_FAILED
            BlockerState.FilterHealth.RUNNING_UNVERIFIED,
            BlockerState.FilterHealth.UNKNOWN,
            -> ShieldStatus.RUNNING_UNVERIFIED
        }

    BlockerState.Phase.FAILED ->
        when (snapshot.failure) {
            BlockerState.FailureKind.VPN_PERMISSION_MISSING -> ShieldStatus.PERMISSION_MISSING
            BlockerState.FailureKind.FILTER_INIT_FAILED -> ShieldStatus.FILTER_FAILED
            BlockerState.FailureKind.VPN_DISCONNECTED -> ShieldStatus.DISCONNECTED
            BlockerState.FailureKind.NONE -> ShieldStatus.DISCONNECTED
        }
}

/**
 * Whether an encrypted-DNS route may still be open, from the same snapshot.
 * Always true unless Device Owner actually closed Private DNS, because a
 * per-app DoH client cannot be detected or blocked from a DNS filter.
 */
fun encryptedDnsBypassPossible(snapshot: BlockerState.Snapshot): Boolean =
    EncryptedDnsExposure(
        privateDnsConfigured = snapshot.privateDnsConfigured,
        privateDnsRestricted = snapshot.privateDnsRestricted,
        selfTestVerified = snapshot.health == BlockerState.FilterHealth.VERIFIED,
    ).bypassPossible