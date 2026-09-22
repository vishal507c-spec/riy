package com.vishal.riy.blocker

/**
 * How strongly one adult-domain match indicates prohibited content. Produced by
 * [Blocklist.classify]; consumed by the protection intelligence layer to decide
 * whether an observation is evidence on its own or only in combination with
 * something else.
 *
 * The distinction exists for exactly one reason — false-positive protection
 * (the Phase 10 requirement that ambiguous activity must not automatically
 * restrict the device):
 *
 *  - [DEFINITIVE] the match is a positive adult-content identification: a rule
 *    from `blocklist.txt`, a dedicated adult TLD, or an adult brand substring.
 *    These cannot plausibly be anything else, so one of them IS evidence and
 *    arms the existing pipeline immediately, exactly as Phase 1 always did.
 *  - [SUSPECT] the match is the *whole-label token* heuristic — a registrable
 *    label such as `adult`, `nude`, `nsfw` or `escort`. That is the one class
 *    the blocklist itself deliberately keeps narrow (whole label of the
 *    registrable part only) because it is ambiguous by nature. One such match
 *    is an OBSERVATION, not evidence: it becomes a protection event only when
 *    [com.vishal.riy.protection.intelligence.EvidenceCorrelator] finds
 *    independent corroboration.
 *
 * Nothing here grades severity and nothing here decides a protection state —
 * those stay with [com.vishal.riy.protection.risk.RiskEngine] and
 * [com.vishal.riy.protection.policy.ProtectionPolicyEngine].
 */
sealed interface BlocklistMatch {

    /** A positively identifying match: blocklist rule, adult TLD or brand substring. */
    data object DEFINITIVE : BlocklistMatch

    /** A whole-label token match: plausible, but ambiguous without corroboration. */
    data object SUSPECT : BlocklistMatch
}
