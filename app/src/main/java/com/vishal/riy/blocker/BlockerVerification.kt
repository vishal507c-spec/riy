package com.vishal.riy.blocker

/**
 * The evidence layer that separates "the VPN service started" from "adult
 * domains are actually being blocked".
 *
 * Everything in this file is pure Kotlin with no Android imports so the rules
 * are directly unit-testable on the desktop JVM.
 *
 * WHY THIS EXISTS
 * ---------------
 * A DNS filter can only ever block a domain whose QNAME it can read. A DoH/DoT
 * query carries the QNAME inside TLS, so no amount of list coverage can stop it
 * without TLS interception, which this app deliberately does not do (it would
 * require a private CA and would mean collecting browsing content). Claiming
 * "fully protected" in that situation is a lie, so the app reports the truth
 * instead: the filter is [running but unverified][BlockerState.FilterHealth.
 * RUNNING_UNVERIFIED] until a self-test proves the DNS path, and it always
 * surfaces whether an encrypted-DNS route is still open.
 */

/** Canary hostnames used by the on-device self-test. */
object SelfTestCanaries {

    /**
     * Must be BLOCKED. Uses the dedicated adult TLD `.xxx`, so the matcher
     * resolves it locally from [Blocklist.ADULT_TLDS] and the query never
     * leaves the device — no real adult site is contacted and no browsing
     * content is involved.
     */
    const val BLOCK = "riy-shield-self-test.xxx"

    /**
     * Must still RESOLVE. `example.com` is IANA's reserved documentation
     * domain; proving it still answers proves the filter is not blackholing
     * ordinary browsing.
     */
    const val ALLOW = "example.com"

    /**
     * True for RIY's own self-test probe.
     *
     * The canary is blocked exactly like any adult domain — that is what proves
     * enforcement — but it must never reach the detection pipeline. Arming a
     * protection event from the app's own health check would be a false
     * positive manufactured by us, and it would escalate into a real
     * restriction and a 2-hour lock on the user's own device.
     */
    fun isSelfTestQuery(domain: String?): Boolean =
        domain != null && BLOCK.equals(Blocklist.normalizeDomain(domain))
}

/** Why the self-test reached the verdict it did. */
enum class SelfTestOutcome {
    /** Blocked domain was sinkholed AND an ordinary domain still resolved. */
    VERIFIED,

    /** The filter answered, but the canary adult domain was NOT blocked. */
    BLOCK_NOT_ENFORCED,

    /** The blocklist is empty or absurdly small — nothing can be blocked. */
    NO_RULES_LOADED,

    /** The ordinary control domain did not resolve — browsing is broken. */
    ALLOW_PATH_BROKEN,

    /** No usable answer came back at all (timeout / filter not reachable). */
    NO_RESPONSE,
}

/**
 * The full, honest result of one self-test run. Kept as data so the UI can show
 * what was actually checked without re-running anything.
 */
data class SelfTestReport(
    val outcome: SelfTestOutcome,
    val ruleCount: Int,
    /** null when the probe got no answer for the blocked canary. */
    val blockSinkholed: Boolean?,
    /** null when the probe got no answer for the ordinary canary. */
    val allowResolved: Boolean?,
) {

    val verified: Boolean get() = outcome == SelfTestOutcome.VERIFIED

    /** Short, non-technical line suitable for the dashboard. */
    fun summary(): String = when (outcome) {
        SelfTestOutcome.VERIFIED ->
            "Verified: $ruleCount rules loaded, blocked site blocked, normal site reachable"
        SelfTestOutcome.BLOCK_NOT_ENFORCED ->
            "Test failed: a blocked site was still reachable"
        SelfTestOutcome.NO_RULES_LOADED ->
            "Test failed: no blocklist rules were loaded"
        SelfTestOutcome.ALLOW_PATH_BROKEN ->
            "Test failed: an ordinary website could not be reached"
        SelfTestOutcome.NO_RESPONSE ->
            "Test incomplete: the filter did not answer its own test"
    }

    /** Maps the evidence onto the health the UI is allowed to display. */
    fun health(): BlockerState.FilterHealth = when (outcome) {
        SelfTestOutcome.VERIFIED -> BlockerState.FilterHealth.VERIFIED
        SelfTestOutcome.BLOCK_NOT_ENFORCED,
        SelfTestOutcome.NO_RULES_LOADED,
        SelfTestOutcome.ALLOW_PATH_BROKEN,
        -> BlockerState.FilterHealth.SELF_TEST_FAILED

        SelfTestOutcome.NO_RESPONSE -> BlockerState.FilterHealth.RUNNING_UNVERIFIED
    }

    companion object {
        /**
         * Pure verdict function. [blockSinkholed] / [allowResolved] are null when
         * the corresponding probe produced no answer.
         *
         * A run with too few rules can never be called verified, no matter what
         * the probes say — an empty list that "blocks nothing" and an empty list
         * that "blocks everything" look identical from the outside.
         */
        fun evaluate(
            ruleCount: Int,
            blockSinkholed: Boolean?,
            allowResolved: Boolean?,
        ): SelfTestReport {
            val report = SelfTestReport(
                outcome = decide(ruleCount, blockSinkholed, allowResolved),
                ruleCount = ruleCount,
                blockSinkholed = blockSinkholed,
                allowResolved = allowResolved,
            )
            return report
        }

        private fun decide(
            ruleCount: Int,
            blockSinkholed: Boolean?,
            allowResolved: Boolean?,
        ): SelfTestOutcome {
            if (ruleCount < MIN_PLAUSIBLE_RULES) return SelfTestOutcome.NO_RULES_LOADED
            if (blockSinkholed == null || allowResolved == null) return SelfTestOutcome.NO_RESPONSE
            if (!blockSinkholed) return SelfTestOutcome.BLOCK_NOT_ENFORCED
            if (!allowResolved) return SelfTestOutcome.ALLOW_PATH_BROKEN
            return SelfTestOutcome.VERIFIED
        }

        /**
         * Below this the blocklist is not a blocklist. Kept far below the real
         * asset size (tens of thousands of domains) so it only trips on a
         * truncated/corrupt load.
         */
        const val MIN_PLAUSIBLE_RULES = 1_000
    }
}

/**
 * The encrypted-DNS (DoH/DoT) risk, derived from facts only — never assumed
 * away.
 */
data class EncryptedDnsExposure(
    /** Android "Private DNS" is set to a custom DNS-over-TLS provider. */
    val privateDnsConfigured: Boolean,
    /** RIY is Device Owner and the private-DNS user restriction is in force. */
    val privateDnsRestricted: Boolean,
    /** The filter has proven it blocks, end to end, through the real tun. */
    val selfTestVerified: Boolean,
) {

    /**
     * True when an encrypted-DNS route may still reach a resolver unfiltered.
     *
     * This is deliberately conservative. A per-application DoH client (Chrome's
     * own "Secure DNS", for example) is invisible to a DNS filter and is NOT
     * closed by the Android Private DNS setting, so only the Device Owner
     * restriction — which removes the Settings toggle — can turn this false,
     * and even then only for the system resolver.
     */
    val bypassPossible: Boolean
        get() = !privateDnsRestricted

    /**
     * True when the filter is working but we cannot prove every app's DNS goes
     * through it. This is the "running, but bypass protection unverified" state
     * the dashboard must show instead of claiming full protection.
     */
    val unverified: Boolean get() = !selfTestVerified || bypassPossible

    fun summary(): String = when {
        privateDnsRestricted ->
            "Device policy blocks changing Private DNS"
        privateDnsConfigured ->
            "Private DNS (DoT) is configured on this device"
        else ->
            "Apps with their own Secure DNS can bypass any DNS filter"
    }
}