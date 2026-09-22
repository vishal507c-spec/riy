package com.vishal.riy.protection.intelligence

import android.content.Context
import com.vishal.riy.blocker.Blocklist
import com.vishal.riy.protection.ProtectionEventProcessor
import com.vishal.riy.protection.ProtectionIntegrationResult
import com.vishal.riy.protection.adultDomainLookupEvent
import com.vishal.riy.protection.corroboratedAdultContentEvent
import com.vishal.riy.protection.recovery.DefaultRecoveryService
import java.util.UUID

/**
 * THE Phase 10 detection front-end. It sits between the one place an
 * adult-content request is actually observable — the filtering VPN's DNS layer
 * — and the EXISTING protection pipeline, and it FEEDS that pipeline instead of
 * duplicating any part of it:
 *
 *     DNS match → [Blocklist.classify]
 *         → [SignalObservation]            (OBSERVATION tier — recorded, never
 *                                            sufficient on its own when ambiguous)
 *         → [EvidenceCorrelator]            (corroboration? deterministic yes/no)
 *         → ProtectionEvent                 (EVIDENCE — only on yes)
 *         → [ProtectionEventProcessor]      (the EXISTING, authoritative chain)
 *             → RiskEngine → PolicyEngine → LockEngine → Enforcement → UI
 *
 * WHAT THIS CLASS OWNS: the OBSERVATION-to-EVIDENCE gate. Nothing more. It
 * holds no risk rule, no policy rule, no deadline, no state machine, no
 * enforcement reference and no UI state. Every consequence of a confirmed
 * observation stays exactly where Phases 1-9 put it:
 *
 *  - detection point        : [com.vishal.riy.blocker.BlockerVpnService]
 *  - match classification   : [Blocklist.classify]
 *  - corroboration rules    : [EvidenceCorrelator] (pure, deterministic)
 *  - observation persistence: [ObservationStore]
 *  - risk grading           : [com.vishal.riy.protection.risk.RiskEngine]
 *  - state transitions      : [com.vishal.riy.protection.policy.ProtectionPolicyEngine]
 *  - the 2-hour deadline    : [com.vishal.riy.lock.LockEngine] / [com.vishal.riy.lock.LockStore]
 *  - restricted lifecycle   : [com.vishal.riy.protection.restricted.RestrictedModeController]
 *  - Android enforcement    : [com.vishal.riy.protection.enforcement.AndroidEnforcementEngine]
 *
 * NO DUPLICATE ANYTHING. This class computes no `now + duration`, starts no
 * timer, posts no delayed task and never touches DevicePolicyManager. It cannot
 * arm, extend or shorten a deadline; it cannot even decide a state — it only
 * decides whether an observation is ready to become an event, and then hands
 * the event to the pipeline that already owned everything downstream.
 *
 * PROCESS DEATH. The observation store is persisted, so a fresh intelligence
 * layer reading the same store sees the same observations a recovered process
 * would. Corroboration is therefore not lost when the RIY process dies.
 */
class ProtectionIntelligence(

    private val blocklist: Blocklist,

    private val correlator: EvidenceCorrelator,

    private val store: ObservationStore,

    private val pipeline: ProtectionEventProcessor,

    private val clock: () -> Long = System::currentTimeMillis,

    private val eventId: () -> String = { UUID.randomUUID().toString() },

) {

    /**
     * The ONE entry point the detection layer offers: one blocklist-matched DNS
     * lookup was observed at [domain]. The caller (the VPN filter loop) has
     * ALREADY written the blocking 0.0.0.0 answer; this method only decides
     * whether the observation is evidence yet.
     *
     * @return the pipeline's result when the observation produced a protection
     *   event, or null when it was recorded as a bare observation (an
     *   uncorroborated suspect, or a duplicate absorbed by the store's dedup).
     *   A definitive match always returns a non-null result.
     */
    @Synchronized
    fun onAdultDomainLookup(domain: String): ProtectionIntegrationResult? {
        val now = clock()

        // HOW strongly did the blocklist identify this host? This is the single
        // fact that separates "evidence" from "observation".
        val match = blocklist.classify(domain) ?: return null

        val observation = SignalObservation(
            domain = Blocklist.normalizeDomain(domain),
            matchClass = match,
            timestamp = now,
        )

        // The store dedups the A/AAAA/retry burst and keeps the earliest
        // timestamp. An absorbed duplicate carries no new information, so the
        // earlier observation's verdict stands and no event is produced here.
        val recorded = store.load()
        if (!store.append(observation)) return null

        return when (val verdict = correlator.evaluate(recorded, observation, now)) {
            // A definitive match is evidence on its own: straight into the
            // EXISTING event the pipeline has always consumed.
            is CorrelationVerdict.EvidenceOnItsOwn ->
                pipeline.submit(adultDomainLookupEvent(verdict.observation.domain, now, eventId()))

            // A corroborated suspect becomes evidence through the new event
            // type, graded by the existing risk engine like any other.
            is CorrelationVerdict.Corroborated ->
                pipeline.submit(
                    corroboratedAdultContentEvent(verdict.observation.domain, now, eventId()),
                )

            // One weak signal alone. Recorded; no protection event. This is the
            // false-positive guarantee in its entirety.
            is CorrelationVerdict.ObservationOnly -> null
        }
    }

    /** The observations currently on record, for diagnostics only. */
    fun observations(): List<SignalObservation> = store.load()

    companion object {

        private const val BLOCKLIST_ASSET = "blocklist.txt"

        /**
         * The production graph. It builds the EXISTING pipeline exactly once
         * ([ProtectionEventProcessor.forContext]) and puts this layer in front
         * of it, so the pipeline's one state store, one lock store and one
         * enforcement engine stay the only owners of those concerns.
         */
        fun forContext(context: Context): ProtectionIntelligence {
            val app = context.applicationContext
            val blocklist = app.assets.open(BLOCKLIST_ASSET).bufferedReader().useLines {
                Blocklist(Blocklist.parseRules(it))
            }

            return ProtectionIntelligence(
                blocklist = blocklist,
                correlator = EvidenceCorrelator(),
                store = PrefsObservationStore(app),
                pipeline = ProtectionEventProcessor.forContext(app),
            )
        }

        /**
         * THE production recovery graph — the EXISTING one. Re-exported so the
         * service has a single construction point beside the pipeline it
         * recovers. Nothing here is a second deadline, a second policy engine
         * or a second enforcement engine.
         */
        fun recoveryForContext(context: Context): DefaultRecoveryService =
            ProtectionEventProcessor.recoveryForContext(context.applicationContext)
    }
}
