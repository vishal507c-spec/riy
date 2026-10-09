package com.vishal.riy.blocker

import java.util.concurrent.atomic.AtomicReference

/**
 * Single source of truth for the REAL protection state. The VPN service
 * reports transitions through [update]; the Compose UI observes [Snapshot].
 * The UI never sets the phase directly, so a fake "Protection ON" is
 * impossible.
 */
object BlockerState {

    enum class Phase {
        /** Protection has never been enabled (or the user turned it off). */
        OFF,

        /** VPN establishment in progress (permission granted, handshake running). */
        CONNECTING,

        /**
         * The tun is up and the filter loop is starting, but the blocklist is
         * still being loaded and no query can be answered yet. Distinguished
         * from [CONNECTED] because a filter that has not finished
         * initialization blocks nothing yet.
         */
        INITIALIZING,

        /** tun interface is up and DNS filtering is running. */
        CONNECTED,

        /** Service failed (VPN revoked / permission missing); protection is NOT active. */
        FAILED,
    }

    /**
     * What the filter has actually PROVEN about itself, as opposed to what it
     * merely claims.
     *
     * [UNKNOWN] is the honest default: nothing has been verified yet. The UI
     * must never upgrade it to [VERIFIED] on its own — only a successful
     * end-to-end self-test does that (see [SelfTestReport]).
     */
    enum class FilterHealth {
        /** No self-test has completed yet (startup, or it has not run). */
        UNKNOWN,

        /**
         * The filter loop is running, but it has NOT been proven to block. In
         * particular an app using encrypted DNS (DoH/DoT) bypasses hostname
         * inspection entirely, so this is the most a DNS filter can honestly
         * claim without a platform-level restriction closing that route.
         */
        RUNNING_UNVERIFIED,

        /**
         * A self-test resolved a canary adult domain through the real tun and
         * received the block answer, while an ordinary domain still resolved.
         * This proves the DNS path is enforcing; it does NOT prove that every
         * browser is using that DNS path.
         */
        VERIFIED,

        /** The self-test ran and did NOT get the expected answers. */
        SELF_TEST_FAILED,
    }

    /**
     * WHY the shield is not protecting, as a closed set rather than free text.
     * The dashboard has to tell "you never granted VPN permission" apart from
     * "the filter failed to start": different problems, different fixes, and
     * lumping them into one grey "not protected" state is exactly how a user
     * ends up believing they are protected when they are not.
     */
    enum class FailureKind {
        /** Nothing is wrong: protection is off or not yet requested. */
        NONE,

        /** VPN consent was never granted, was revoked, or another VPN holds it. */
        VPN_PERMISSION_MISSING,

        /** The tun could not be established although permission exists. */
        VPN_DISCONNECTED,

        /** The tun is up but the filter itself failed (blocklist unreadable...). */
        FILTER_INIT_FAILED,
    }

    data class Snapshot(
        val phase: Phase = Phase.OFF,
        val failureReason: String? = null,
        val failure: FailureKind = FailureKind.NONE,
        val health: FilterHealth = FilterHealth.UNKNOWN,
        /** Rule count actually loaded; 0 means the filter has no rules yet. */
        val ruleCount: Int = 0,
        /** Human-readable result of the last self-test, for diagnostics/UI. */
        val selfTestSummary: String? = null,
        /**
         * True when the OS reports a custom Private DNS (DNS-over-TLS)
         * configuration, which can move system DNS off the filtered resolver on
         * cellular. Never cleared silently — it is reported honestly.
         */
        val privateDnsConfigured: Boolean = false,
        /**
         * True when RIY holds Device Owner AND the private-DNS user restriction
         * is actually in force, which is what closes the encrypted-DNS route on
         * a managed device.
         */
        val privateDnsRestricted: Boolean = false,
    )

    private val snapshot = AtomicReference(Snapshot())

    /** Current real state; always derived from the service, never the UI. */
    fun current(): Snapshot = snapshot.get()

    fun update(
        phase: Phase,
        failureReason: String? = null,
        failure: FailureKind = failureFor(phase),
        health: FilterHealth = healthFor(phase),
        ruleCount: Int = 0,
        selfTestSummary: String? = null,
        privateDnsConfigured: Boolean = false,
        privateDnsRestricted: Boolean = false,
    ) {
        snapshot.set(
            Snapshot(
                phase = phase,
                failureReason = failureReason,
                failure = failure,
                health = health,
                ruleCount = ruleCount,
                selfTestSummary = selfTestSummary,
                privateDnsConfigured = privateDnsConfigured,
                privateDnsRestricted = privateDnsRestricted,
            ),
        )
    }

    /**
     * The failure a phase implies on its own. A phase alone cannot distinguish
     * "permission revoked" from "another VPN is active", so those are passed
     * explicitly by [BlockerVpnService]; the default is only the coarse reading.
     */
    fun failureFor(phase: Phase): FailureKind = when (phase) {
        Phase.FAILED -> FailureKind.VPN_DISCONNECTED
        Phase.OFF, Phase.CONNECTING, Phase.INITIALIZING, Phase.CONNECTED -> FailureKind.NONE
    }

    /**
     * The health a phase implies on its own. A phase transition can only ever
     * assert what it knows: `CONNECTED` means the loop is running, which is
     * [FilterHealth.RUNNING_UNVERIFIED] until a self-test says otherwise.
     * `CONNECTING`/`INITIALIZING` know nothing at all.
     */
    fun healthFor(phase: Phase): FilterHealth = when (phase) {
        Phase.CONNECTED -> FilterHealth.RUNNING_UNVERIFIED
        Phase.FAILED -> FilterHealth.SELF_TEST_FAILED
        Phase.OFF, Phase.CONNECTING, Phase.INITIALIZING -> FilterHealth.UNKNOWN
    }
}