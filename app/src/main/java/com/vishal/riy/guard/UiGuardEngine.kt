package com.vishal.riy.guard

import com.vishal.riy.guard.rules.WhatsAppStatusRule

/**
 * The single, GENERIC content-blocking engine every in-app UI guard shares.
 *
 * It owns no detection knowledge at all: detection lives entirely in
 * [UiGuardRule] implementations, so a new blocked surface is "another rule",
 * never another blocking system.
 *
 * TWO PHASES, both pure functions so the whole policy is JVM-unit-testable:
 *
 *  1. [evaluate] — ask every enabled rule about a [ScreenSnapshot] and keep the
 *     strongest verdict. A screen that no enabled rule recognises yields
 *     [GuardVerdict.ALLOW], which is the default outcome and therefore the
 *     safe one.
 *
 *  2. [step] — turn a verdict into the single safest action to take next.
 *
 * SAFETY PRINCIPLE: only [GuardConfidence.CONFIRMED] may produce an action.
 * [GuardConfidence.CANDIDATE] and [GuardConfidence.NONE] are always idle, so a
 * vaguely similar normal screen is never touched.
 *
 * ACTION POLICY (safest available action, in the order the spec requires):
 *  - a confirmed ENTRY_POINT means the user has just tapped the way in. The
 *    other app already dispatched its own click, so we do NOT fire blindly:
 *    we ARM and wait for the navigation to land;
 *  - a confirmed LIST/VIEWER means the content is already on screen, so we act
 *    immediately with one back-out, which returns the user to the previous
 *    safe screen inside the same app;
 *  - we never force the app closed, and we never exceed
 *    [MAX_CONSECUTIVE_BACKS] back-outs per single user attempt.
 */
class UiGuardEngine(
    private val rules: List<UiGuardRule> = DEFAULT_RULES,
) {

    /** Evaluates one screen against every enabled rule. Never throws. */
    fun evaluate(
        snapshot: ScreenSnapshot,
        isTargetEnabled: (String) -> Boolean = { true },
    ): GuardVerdict {
        if (snapshot.packageName.isBlank()) return GuardVerdict.ALLOW
        var best = GuardVerdict.ALLOW
        for (rule in rules) {
            if (snapshot.packageName !in rule.packages) continue
            if (!isTargetEnabled(rule.targetId)) continue
            val verdict = try {
                rule.evaluate(snapshot)
            } catch (_: Throwable) {
                // A rule must never be able to break protection.
                GuardVerdict.ALLOW
            }
            if (verdict.confidence.ordinal > best.confidence.ordinal) best = verdict
        }
        return best
    }

    /**
     * The action step for one verdict, plus the updated state to keep. Pure:
     * the caller owns time ([nowMs]) and performs the returned action.
     */
    fun step(
        state: GuardState,
        verdict: GuardVerdict,
        nowMs: Long,
    ): GuardStepResult {
        // An armed entry that never landed must release itself rather than stay
        // pending forever and fire later against an unrelated screen.
        val current = if (state.armed && nowMs - state.armedAtMs > ENTRY_WINDOW_MS) {
            state.copy(armed = false, consecutiveBacks = 0)
        } else {
            state
        }

        return when {
            !verdict.isConfirmed -> GuardStepResult(
                // Release any pending arming and the back-out budget: the user
                // is on an ordinary screen again.
                state = current.copy(armed = false, consecutiveBacks = 0),
                step = GuardStep.IDLE,
                shouldNotify = false,
            )

            verdict.destination == GuardDestination.ENTRY_POINT && !current.armed ->
                GuardStepResult(
                    state = current.copy(
                        armed = true,
                        armedAtMs = nowMs,
                        // A fresh attempt earns a fresh back-out budget.
                        consecutiveBacks = 0,
                    ),
                    step = GuardStep.ARMED,
                    shouldNotify = false,
                )

            verdict.destination == GuardDestination.ENTRY_POINT -> GuardStepResult(
                state = current,
                step = GuardStep.ARMED,
                shouldNotify = false,
            )

            current.consecutiveBacks >= MAX_CONSECUTIVE_BACKS -> GuardStepResult(
                state = current.copy(armed = false),
                step = GuardStep.IDLE,
                shouldNotify = false,
            )

            else -> GuardStepResult(
                state = current.copy(
                    armed = false,
                    consecutiveBacks = current.consecutiveBacks + 1,
                ),
                step = GuardStep.BACK_NOW,
                shouldNotify = true,
            )
        }
    }

    companion object {
        /** Shipped rules. Adding a target adds an entry here. */
        val DEFAULT_RULES: List<UiGuardRule> = listOf(WhatsAppStatusRule)

        /**
         * How long a tapped entry may stay pending before we give up on it. The
         * window is generous enough for a slow launch and short enough that a
         * stale arming can never fire against a later, unrelated screen.
         */
        const val ENTRY_WINDOW_MS = 2_000L

        /** Back-outs allowed for one single user attempt, then we stop. */
        const val MAX_CONSECUTIVE_BACKS = 3
    }
}

/** The engine's tiny, serialisable-per-event state. */
data class GuardState(
    /** Waiting for a tapped entry to land on the blocked destination. */
    val armed: Boolean = false,

    /** When the arming started ([UiGuardEngine.ENTRY_WINDOW_MS] is the budget). */
    val armedAtMs: Long = 0L,

    /** Back-outs already spent on the current attempt. */
    val consecutiveBacks: Int = 0,
)

/** What the caller should actually DO right now. */
enum class GuardStep {
    /** Do nothing at all. */
    IDLE,

    /** Remember that an entry was tapped; act when the destination appears. */
    ARMED,

    /** Navigate back out of the blocked destination now. */
    BACK_NOW,
}

/** The result of one [UiGuardEngine.step] call. */
data class GuardStepResult(
    val state: GuardState,
    val step: GuardStep,
    /** True when a brief user-facing notice is appropriate for this action. */
    val shouldNotify: Boolean,
)
