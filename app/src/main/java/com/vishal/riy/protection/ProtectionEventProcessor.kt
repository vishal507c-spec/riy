package com.vishal.riy.protection

import android.content.Context
import com.vishal.riy.lock.LockEngine
import com.vishal.riy.lock.LockStore
import com.vishal.riy.lock.PrefsLockStore
import com.vishal.riy.protection.enforcement.AndroidEnforcementEngine
import com.vishal.riy.protection.enforcement.LockTaskPolicy
import com.vishal.riy.protection.enforcement.PackageManagerAppPolicyResolver
import com.vishal.riy.protection.enforcement.PolicyApplicationResult
import com.vishal.riy.protection.enforcement.platform.AndroidDevicePolicyBoundary
import com.vishal.riy.protection.enforcement.platform.AndroidPackageDiscoveryBoundary
import com.vishal.riy.protection.event.ProtectionEvent
import com.vishal.riy.protection.event.ProtectionEventSource
import com.vishal.riy.protection.event.ProtectionEvidenceType
import com.vishal.riy.protection.escalation.EscalationDecision
import com.vishal.riy.protection.escalation.ProtectionEscalationEngine
import com.vishal.riy.protection.escalation.ProtectionEscalationStore
import com.vishal.riy.protection.events.ProtectionEventStore
import com.vishal.riy.protection.events.ProtectionLogEvent
import com.vishal.riy.protection.events.PrefsProtectionEventStore
import com.vishal.riy.protection.policy.ProtectionDecision
import com.vishal.riy.protection.policy.ProtectionPolicyEngine
import com.vishal.riy.protection.policy.ProtectionState
import com.vishal.riy.protection.policy.DefaultProtectionPolicyEngine
import com.vishal.riy.protection.recovery.DefaultRecoveryService
import com.vishal.riy.protection.restricted.DefaultRestrictedModeController
import com.vishal.riy.protection.restricted.RestrictedModeController
import com.vishal.riy.protection.risk.DefaultRiskEngine
import com.vishal.riy.protection.risk.RiskAssessment
import com.vishal.riy.protection.risk.RiskEngine
import com.vishal.riy.protection.risk.RiskLevel
import com.vishal.riy.protection.state.ProtectionSession
import com.vishal.riy.protection.state.ProtectionStateStore
import com.vishal.riy.protection.state.PrefsProtectionStateStore
import com.vishal.riy.protection.escalation.PrefsProtectionEscalationStore
import java.util.UUID

/**
 * The single Phase 5 integration point. It connects the ONE genuine protection
 * event source that exists to the Phase 3 policy engine and the Phase 4
 * enforcement controller, and to nothing else:
 *
 *     DNS adult-domain detection  (BlockerVpnService — detection only)
 *         → ProtectionEvent        (normalised evidence)
 *         → RiskEngine             (severity of that one observation)
 *         → ProtectionPolicyEngine (the ONLY state-transition authority)
 *         → ProtectionSession      (persisted by ProtectionStateStore)
 *         → RestrictedModeController (restricted-mode lifecycle only)
 *         → AndroidEnforcementEngine (the ONLY component touching DPM)
 *
 * WHAT THIS CLASS OWNS: the order of those steps. Nothing more. It holds no
 * rule, no threshold, no deadline, no allowlist and no DevicePolicyManager
 * reference — every one of those stays with its existing owner:
 *
 *  - detection                : [com.vishal.riy.blocker.BlockerVpnService]
 *  - risk grading             : [RiskEngine]
 *  - state transitions        : [ProtectionPolicyEngine]
 *  - session persistence      : [ProtectionStateStore]
 *  - 2-hour deadline          : [LockEngine] / [LockStore]  (sole authority)
 *  - restricted lifecycle     : [RestrictedModeController]
 *  - Android enforcement      : [AndroidEnforcementEngine]
 *  - event/log persistence    : [ProtectionEventStore]
 *
 * NO DUPLICATE TIMER. When the policy decides to enter a restriction, this
 * class ASKS [LockEngine] for the deadline and then records the same wall-clock
 * value on the session as a mirror (exactly as [ProtectionSession] documents).
 * It never computes `now + duration` itself, never posts a delayed task and
 * never starts a countdown; the policy's [ProtectionDecision.duration] is
 * carried through for the record only.
 *
 * STICKINESS. Repeated evidence arriving while a restricted session is already
 * live is absorbed by the existing policy engine (`requested == currentState`
 * → no change), so this class does not re-arm, extend or shorten the deadline,
 * and it does not create a second session. The dedup of A/AAAA + retries for
 * the same domain is likewise [LockEngine]'s existing rule.
 */
class ProtectionEventProcessor(

    private val riskEngine: RiskEngine,

    private val policyEngine: ProtectionPolicyEngine,

    private val stateStore: ProtectionStateStore,

    private val lockStore: LockStore,

    private val restrictedMode: RestrictedModeController,

    private val eventStore: ProtectionEventStore,

    private val enforcement: AndroidEnforcementEngine,

    private val escalationEngine: ProtectionEscalationEngine? = null,

    private val lockTaskPolicy: LockTaskPolicy = LockTaskPolicy(),

    private val clock: () -> Long = System::currentTimeMillis,

    private val sessionId: () -> String = { UUID.randomUUID().toString() },

) {

    /**
     * The escalation decision for the event currently being processed, or null
     * when no escalation engine is wired. Exposed for tests/diagnostics so the
     * counting can be asserted without touching the store directly.
     */
    @Volatile
    private var lastEscalation: EscalationDecision? = null

    /**
     * Runs one genuine event through the whole chain.
     *
     * @return the policy decision plus the enforcement outcome when a
     *   restriction was actually attempted ([ProtectionIntegrationResult.enforcementResult]
     *   is null when the policy required no restriction).
     */
    @Synchronized
    fun submit(event: ProtectionEvent): ProtectionIntegrationResult {
        val now = clock()
        val assessment = riskEngine.assess(event)

        // The evidence is recorded BEFORE anything is acted upon, so a failure
        // further down the chain can never make a genuine detection invisible.
        // No domain or other private fact is written beyond what the event
        // contract itself carries.
        eventStore.append(
            ProtectionLogEvent(
                eventId = event.eventId,
                type = ProtectionLogEvent.Type.CONTENT_DETECTED,
                timestamp = now,
                message = "${event.evidenceType} via ${event.source}: " +
                    "risk=${assessment.riskLevel}",
            ),
        )

        val currentState = currentState(now)

        // PHASE 6 — ESCALATION COUNTING. The stateful escalation component
        // records this qualifying event and reports whether the accumulated
        // evidence has reached the configured threshold. This happens BEFORE
        // the policy decision, so the policy engine sees the escalated risk
        // level — and ONLY the policy engine performs the actual transition.
        //
        // The counting is a SEPARATE concern from the deadline: a repeated
        // event during a live session updates escalation state but never
        // re-arms, extends or shortens the LockEngine deadline (the stickiness
        // rules below keep guarding that).
        val escalation = escalationEngine?.record(event, assessment)
        lastEscalation = escalation

        val decision = policyEngine.decide(
            escalateAssessment(assessment, escalation, currentState),
            currentState,
        )

        // A decision may keep an already-restricted device in RESTRICTED: its
        // [ProtectionDecision.nextState] is then RESTRICTED with reason
        // [ProtectionDecision.REASON_NO_CHANGE], which is the policy engine's
        // explicit "do nothing" answer. Only a genuine TRANSITION into a
        // restriction arms anything — this is what keeps repeated evidence from
        // re-arming, extending or shortening the live 2-hour window, and what
        // guarantees one session and one deadline, never two.
        if (!decision.entersRestriction ||
            decision.reason == ProtectionDecision.REASON_NO_CHANGE
        ) {
            return ProtectionIntegrationResult(decision, enforcementResult = null)
        }

        // ---- THE DEADLINE -------------------------------------------------
        // LockEngine computes it, LockStore persists it. The session below only
        // mirrors the value produced here; it never becomes a second one.
        //
        // READ DIRECTION: the deadline is read from LockStore only. This class
        // never writes a value it took from the session store back into
        // LockStore, because that would make the mirror an authority over the
        // thing it mirrors. Should the two ever disagree, LockEngine's
        // persisted deadline wins — which is what its documented contract says.
        //
        // REPEATED-EVENT / ESCALATION RULE: when a session is ALREADY live, the
        // existing deadline is preserved as-is. This covers both a repeated
        // detection (absorbed above as NO_CHANGE) and a genuine in-session
        // escalation RESTRICTED → HARDENED: the session is re-recorded with the
        // SAME wall-clock deadline, so escalation changes the enforced policy
        // without re-arming, extending, shortening or duplicating the 2-hour
        // window. LockEngine stays the sole deadline authority.
        val domain = event.metadata[ProtectionEvent.META_DOMAIN].orEmpty()
        val previous = LockEngine.clearIfExpired(lockStore.loadState(), now)
        val sessionAlreadyLive = stateStore.current()
            ?.let { it.state.isRestrictedSession() && !it.isExpired(now) }
            ?: false
        val armed = if (sessionAlreadyLive && LockEngine.isLocked(previous, now)) {
            // A restriction is already in force: keep LockEngine's own
            // persisted deadline untouched. Re-arming here would reset the
            // 2-hour window; the session recorded below mirrors this same
            // value, so RESTRICTED and HARDENED can never mean two timers.
            previous
        } else {
            LockEngine.onPornDetected(previous, now, domain)
        }
        if (armed.lockEndEpochMillis == LockEngine.NO_LOCK) {
            // A genuine detection that did not arm a deadline cannot produce a
            // defensible session; report the failure honestly rather than
            // inventing a competing expiry of our own.
            eventStore.append(
                ProtectionLogEvent(
                    eventId = event.eventId,
                    type = ProtectionLogEvent.Type.INTEGRITY_MISMATCH,
                    timestamp = now,
                    message = "restriction decided but no deadline was armed",
                ),
            )
            return ProtectionIntegrationResult(decision, PolicyApplicationResult.FAILED)
        }
        if (armed !== previous) lockStore.saveState(armed)

        val session = ProtectionSession(
            sessionId = sessionId(),
            state = decision.nextState,
            startTime = now,
            expiryTime = armed.lockEndEpochMillis,
            reason = decision.reason,
            policyVersion = decision.policyVersion,
            allowedPackages = emptyList(),
        )

        // The controller persists the session and delegates the platform write
        // to the enforcement engine; both are idempotent at every layer.
        restrictedMode.enter(session, lockTaskPolicy)
        val applicationResult = enforcement.lastApplicationResultSnapshot()

        if (applicationResult == PolicyApplicationResult.APPLIED) {
            eventStore.append(
                ProtectionLogEvent(
                    eventId = event.eventId,
                    type = ProtectionLogEvent.Type.RESTRICTION_ENTERED,
                    timestamp = now,
                    message = "restricted session ${session.sessionId} entered " +
                        "(${session.reason})",
                ),
            )
        } else {
            // Enforcement did NOT succeed. It is reported as a mismatch rather
            // than swallowed: the policy decision stays authoritative in the
            // state store, the deadline is untouched, and the failure is
            // available through the existing diagnostics/result contracts.
            eventStore.append(
                ProtectionLogEvent(
                    eventId = event.eventId,
                    type = ProtectionLogEvent.Type.INTEGRITY_MISMATCH,
                    timestamp = now,
                    message = "restricted session ${session.sessionId} NOT applied: " +
                        "$applicationResult",
                ),
            )
        }

        return ProtectionIntegrationResult(decision, applicationResult)
    }

    /**
     * Ends the live restriction session. Delegates ENTIRELY to
     * [RestrictedModeController.exit]: normal platform policy first, then the
     * persisted session is dropped. This is the pipeline's own restore path —
     * it originates no decision (only the policy engine's expiry/recovery
     * transitions reach it) and it never touches the lock deadline.
     */
    fun endActiveSession() = restrictedMode.exit()

    // ------------------------------------------------------------- state in

    /**
     * The protection state the policy engine must decide FROM. A persisted
     * session is trusted only while its own state is not time-boxed, or while
     * the authoritative [LockEngine] deadline says the time-boxed window is
     * still open — this class never decides expiry itself.
     */
    private fun currentState(now: Long): ProtectionState {
        val session = stateStore.current() ?: return ProtectionState.NORMAL
        return if (isLive(session, now)) session.state else ProtectionState.NORMAL
    }

    /**
     * A non-restricted session has no deadline and is taken at face value.
     * A restricted one is live only while LockEngine's deadline still runs AND
     * the session's own mirrored window has not closed.
     */
    private fun isLive(session: ProtectionSession, now: Long): Boolean {
        if (!session.state.isRestrictedSession()) return true
        return !session.isExpired(now) && LockEngine.isLocked(lockStore.loadState(), now)
    }

    private fun ProtectionState.isRestrictedSession(): Boolean =
        this == ProtectionState.RESTRICTED || this == ProtectionState.HARDENED

    /**
     * PHASE 6 — the ONLY place the escalation component's decision influences
     * the pipeline. When the accumulated qualifying evidence has reached the
     * configured threshold AND a restricted session is already live, the risk
     * level is lifted from CONFIRMED to [RiskLevel.HARDENED], which the policy
     * engine maps onto the RESTRICTED → HARDENED transition it already
     * validates.
     *
     * The live-session condition is not arbitrary: it mirrors the policy
     * engine's own legal-transition table, where HARDENED is reachable ONLY
     * from RESTRICTED. Escalation therefore never arms a restriction on its
     * own — a detection arriving with no session live still enters RESTRICTED
     * through the ordinary CONFIRMED path, exactly as Phase 5 did, and only
     * the *next* qualifying event escalates the live session.
     */
    private fun escalateAssessment(
        assessment: RiskAssessment,
        escalation: EscalationDecision?,
        currentState: ProtectionState,
    ): RiskAssessment {
        if (escalation == null || !escalation.escalated) return assessment
        if (!assessment.isActionable) return assessment
        if (!currentState.isRestrictedSession()) return assessment
        return assessment.copy(riskLevel = RiskLevel.HARDENED)
    }

    /** The most recent escalation decision, for diagnostics only. */
    fun lastEscalationDecision(): EscalationDecision? = lastEscalation

    companion object {

        /**
         * The production graph: builds each existing component exactly once and
         * wires it behind this processor. Sharing one state store, one lock
         * store and one enforcement engine is what keeps a single authority for
         * each concern — no component gets a private copy of the truth.
         */
        fun forContext(context: Context): ProtectionEventProcessor {
            val riyPackageName = context.applicationContext.packageName
            val discovery = AndroidPackageDiscoveryBoundary(context)
            val enforcement = AndroidEnforcementEngine(
                devicePolicy = AndroidDevicePolicyBoundary(context),
                discovery = discovery,
                appPolicyResolver = PackageManagerAppPolicyResolver(discovery, riyPackageName),
                riyPackageName = riyPackageName,
            )
            val stateStore: ProtectionStateStore = PrefsProtectionStateStore(context)
            val lockStore: LockStore = PrefsLockStore(context)
            val escalationStore: ProtectionEscalationStore = PrefsProtectionEscalationStore(context)

            return ProtectionEventProcessor(
                riskEngine = DefaultRiskEngine(),
                policyEngine = DefaultProtectionPolicyEngine(),
                stateStore = stateStore,
                lockStore = lockStore,
                escalationEngine = ProtectionEscalationEngine(escalationStore),
                restrictedMode = DefaultRestrictedModeController(
                    enforcement,
                    stateStore,
                    lockStore,
                ),
                eventStore = PrefsProtectionEventStore(context),
                enforcement = enforcement,
            )
        }

        /**
         * THE Phase 6 production recovery graph. It builds the SAME shared
         * components [forContext] builds — one state store, one lock store, one
         * enforcement engine — so recovery reads exactly the truth the
         * pipeline wrote, and wires them into the single recovery orchestrator.
         *
         * Nothing here is a second deadline, a second policy engine or a second
         * enforcement engine: every owner is the existing one.
         */
        fun recoveryForContext(context: Context): DefaultRecoveryService {
            val riyPackageName = context.applicationContext.packageName
            val discovery = AndroidPackageDiscoveryBoundary(context)
            val enforcement = AndroidEnforcementEngine(
                devicePolicy = AndroidDevicePolicyBoundary(context),
                discovery = discovery,
                appPolicyResolver = PackageManagerAppPolicyResolver(discovery, riyPackageName),
                riyPackageName = riyPackageName,
            )
            val stateStore: ProtectionStateStore = PrefsProtectionStateStore(context)
            val lockStore: LockStore = PrefsLockStore(context)

            return DefaultRecoveryService(
                restrictedMode = DefaultRestrictedModeController(
                    enforcement,
                    stateStore,
                    lockStore,
                ),
                lockStore = lockStore,
                enforcement = enforcement,
                eventStore = PrefsProtectionEventStore(context),
            )
        }
    }
}

/**
 * One observation's journey through the chain: what the policy decided, and —
 * when the decision asked for a restriction — what the enforcement layer
 * actually achieved, using the existing [PolicyApplicationResult] vocabulary so
 * a failure is never reported as success.
 */
data class ProtectionIntegrationResult(

    val decision: ProtectionDecision,

    val enforcementResult: PolicyApplicationResult?,
)

/**
 * THE genuine protection event RIY can observe, built from the one signal that
 * is actually visible to a non-MITM Android app: a DNS query for an adult
 * domain that the filtering VPN's [com.vishal.riy.blocker.Blocklist] already
 * matched. Nothing else is fabricated here — no keyword, URL or image signal,
 * because none of those is observable without decrypting TLS.
 *
 * The matched domain is carried under the event contract's own
 * [ProtectionEvent.META_DOMAIN] key and nowhere else.
 */
fun adultDomainLookupEvent(

    domain: String,

    now: Long,

    eventId: String = UUID.randomUUID().toString(),

): ProtectionEvent = ProtectionEvent(
    eventId = eventId,
    timestamp = now,
    source = ProtectionEventSource.DNS_FILTER,
    evidenceType = ProtectionEvidenceType.ADULT_DOMAIN_DNS_LOOKUP,
    // A blocklist match is definitive evidence, not a probability.
    confidence = ProtectionEvent.Confidence.CERTAIN,
    metadata = mapOf(ProtectionEvent.META_DOMAIN to domain),
)

/**
 * The one Phase 10 addition to the event vocabulary: an ambiguous (whole-label
 * token) adult-domain signal that
 * [com.vishal.riy.protection.intelligence.EvidenceCorrelator] has already
 * corroborated with an independent observation. Built by
 * [com.vishal.riy.protection.intelligence.ProtectionIntelligence] and consumed
 * by this processor exactly like the event above — same contract, same
 * metadata key, same minimum information. No new authority is introduced: the
 * corroboration was decided upstream by deterministic rules, and the severity
 * of the resulting event is graded by the existing
 * [com.vishal.riy.protection.risk.RiskEngine].
 */
fun corroboratedAdultContentEvent(

    domain: String,

    now: Long,

    eventId: String = UUID.randomUUID().toString(),

): ProtectionEvent = ProtectionEvent(
    eventId = eventId,
    timestamp = now,
    source = ProtectionEventSource.DNS_FILTER,
    evidenceType = ProtectionEvidenceType.CORROBORATED_ADULT_CONTENT,
    // Two independent observations of an ambiguous signal are evidence, not a
    // probability — no invented score is attached.
    confidence = ProtectionEvent.Confidence.CERTAIN,
    metadata = mapOf(ProtectionEvent.META_DOMAIN to domain),
)
