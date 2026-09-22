package com.vishal.riy.protection.intelligence

import com.vishal.riy.blocker.BlocklistMatch

/**
 * THE deterministic evidence correlation layer. It is the only component that
 * answers "does this observation now amount to evidence?" — and it answers it
 * with rules, not with a score.
 *
 * ARCHITECTURE POSITION. This class sits strictly BEFORE the existing pipeline:
 *
 *     observation → [EvidenceCorrelator] (corroboration? yes/no)
 *                       → ProtectionEvent (only on YES)
 *                           → RiskEngine → PolicyEngine → LockEngine → …
 *
 * It owns NO state machine, NO deadline, NO timer, NO risk grade and NO policy
 * decision. Corroboration is a pure function of (the observations, the new
 * observation, the clock); the same inputs always yield the same verdict, which
 * is what makes every rule unit-testable on the JVM.
 *
 * THE ONE RULE THAT MATTERS — a single weak signal never becomes a strong
 * confirmation:
 *  - A [BlocklistMatch.DEFINITIVE] observation is evidence BY ITSELF and needs
 *    no correlation (see [CorrelationVerdict.EvidenceOnItsOwn]). It arms the
 *    existing pipeline exactly as Phase 1 always did; this layer neither adds
 *    nor removes anything from that.
 *  - A [BlocklistMatch.SUSPECT] observation is an observation ONLY. It becomes
 *    evidence when — and only when — an EARLIER suspect observation inside the
 *    detection window independently corroborates it (see
 *    [CorrelationVerdict.Corroborated]). Corroboration is always attributed to
 *    the observation that COMPLETES the pattern, so exactly one event is
 *    produced for one correlated episode — never one per observation.
 *
 * WHY CORROBORATION LOOKS ONLY BACKWARD. Each observation is evaluated exactly
 * once, when it arrives, against the observations already recorded. An earlier
 * suspect that found no corroboration produced nothing and is simply still on
 * the record; the later observation that completes the pair produces the single
 * event. No "consumed" flags, no second pass, no possibility of the same
 * episode arming the pipeline twice.
 *
 * DEFINITIVE OBSERVATIONS NEVER CORROBORATE A SUSPECT. A definitive match
 * already produces its own event through the definitive path, so letting it
 * also confirm a pending suspect would double-count one episode. The two tiers
 * are therefore independent: the definitive tier protects exactly as before,
 * and the suspect tier adds coverage only for the ambiguous class nothing else
 * covers.
 */
class EvidenceCorrelator {

    /**
     * Evaluates [candidate] against the [recorded] observations.
     *
     * @param recorded everything the store currently holds, oldest first. The
     *     caller is expected to have already expired records outside the
     *     detection window (the store does this at read time), but this method
     *     applies the same window itself so a stale list can never change the
     *     verdict.
     * @param candidate the observation being evaluated.
     * @param now the clock value the evaluation is performed at.
     */
    fun evaluate(
        recorded: List<SignalObservation>,
        candidate: SignalObservation,
        now: Long,
    ): CorrelationVerdict {
        // A definitive match is evidence on its own; no second signal needed,
        // no score, no discounting.
        if (candidate.isDefinitive) return CorrelationVerdict.EvidenceOnItsOwn(candidate)

        // Only the ambiguous class reaches the correlation rules.
        val earlier = recorded.filter { it.timestamp <= candidate.timestamp }

        // Independent corroboration: a DIFFERENT domain inside the window.
        if (earlier.any { it.isSuspect && it.domain != candidate.domain && inWindow(it, now) }) {
            return CorrelationVerdict.Corroborated(candidate, CorroborationKind.DISTINCT_DOMAIN)
        }

        // Repeat corroboration: the SAME domain seen again past the dedup
        // window — a genuine repeat navigation, not one page load's burst.
        val sameDomain = earlier.filter { it.isSuspect && it.domain == candidate.domain }
        if (sameDomain.any { candidate.timestamp - it.timestamp >= ProtectionCorrelationWindow.SAME_DOMAIN_REPEAT_MS &&
                inWindow(it, now)
        }) {
            return CorrelationVerdict.Corroborated(
                candidate,
                CorroborationKind.SAME_DOMAIN_REPEAT,
            )
        }

        // One weak signal on its own stays an observation. This is the
        // false-positive guarantee: ambiguous activity does not lock the device.
        return CorrelationVerdict.ObservationOnly(candidate)
    }

    private fun inWindow(observation: SignalObservation, now: Long): Boolean =
        now - observation.timestamp <= ProtectionCorrelationWindow.CORRELATION_WINDOW_MS
}

/**
 * The verdict for one observation. Pure Kotlin; it can execute nothing.
 */
sealed interface CorrelationVerdict {

    /** The observation this verdict describes. */
    val observation: SignalObservation

    /**
     * A definitive observation: evidence on its own, no corroboration required.
     * The existing pipeline is armed for it immediately.
     */
    data class EvidenceOnItsOwn(override val observation: SignalObservation) : CorrelationVerdict

    /**
     * A suspect observation that IS independently corroborated. It may now
     * become a protection event through the same existing pipeline.
     *
     * @param kind which deterministic rule fired, for logs and tests.
     */
    data class Corroborated(
        override val observation: SignalObservation,
        val kind: CorroborationKind,
    ) : CorrelationVerdict

    /**
     * A suspect observation with NO independent corroboration. It is recorded
     * and nothing else: no protection event, no restriction, no state change.
     */
    data class ObservationOnly(override val observation: SignalObservation) : CorrelationVerdict
}

/**
 * Which deterministic correlation rule fired. Diagnostic only; it drives no
 * decision and changes no outcome.
 */
enum class CorroborationKind {

    /** Two suspect observations for two DIFFERENT domains inside the window. */
    DISTINCT_DOMAIN,

    /** The same suspect domain seen again past the dedup window. */
    SAME_DOMAIN_REPEAT,
}
