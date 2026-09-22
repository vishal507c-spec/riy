package com.vishal.riy.protection.intelligence

import com.vishal.riy.blocker.BlocklistMatch
import com.vishal.riy.protection.policy.ProtectionPolicy

/**
 * ONE raw observation the protection system made, before any decision about
 * whether it is evidence. This is the OBSERVATION tier the Phase 10
 * architecture keeps separate from EVIDENCE ([com.vishal.riy.protection.event.ProtectionEvent])
 * and from a CONFIRMED PROTECTION EVENT: a single ambiguous observation must
 * never become a restriction on its own.
 *
 * Pure Kotlin, no Android reference. Carries the minimum the correlation rules
 * need and nothing else.
 *
 * PRIVACY / DATA MINIMISATION. The only fact recorded about a matched lookup is
 * the domain and the match class — the same minimum information the existing
 * event architecture already carries under
 * [com.vishal.riy.protection.event.ProtectionEvent.META_DOMAIN] and that
 * [com.vishal.riy.lock.LockState.lastDomain] already persists. Deliberately
 * NEVER recorded: search text, query strings, full URLs, paths, page content,
 * TLS bytes, app names, notifications, messages, passwords or anything else
 * that is not a blocklist-matched hostname. Every domain this store can hold is
 * by construction a string the production [com.vishal.riy.blocker.Blocklist]
 * matched first.
 *
 * @param domain       the matched, normalised hostname (never a URL).
 * @param matchClass   how strongly the blocklist identified it.
 * @param timestamp    epoch ms when the lookup was observed.
 * @param policyVersion the policy that was live when it was recorded.
 */
data class SignalObservation(

    val domain: String,

    val matchClass: BlocklistMatch,

    val timestamp: Long,

    val policyVersion: Int = ProtectionPolicy.CURRENT_POLICY_VERSION,

) {

    /** True when this observation positively identifies adult content on its own. */
    val isDefinitive: Boolean
        get() = matchClass == BlocklistMatch.DEFINITIVE

    /** True when this observation is the ambiguous class that needs corroboration. */
    val isSuspect: Boolean
        get() = matchClass == BlocklistMatch.SUSPECT
}
