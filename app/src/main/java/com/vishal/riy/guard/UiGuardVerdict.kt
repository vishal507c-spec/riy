package com.vishal.riy.guard

/**
 * How strongly a rule believes a screen is the destination it blocks.
 *
 * The ordering is meaningful and the engine compares it directly:
 *  - [NONE]      → not this target; do nothing.
 *  - [CANDIDATE] → something *resembles* it. Logged during development, and
 *                  deliberately NOT acted upon. A false positive on a normal
 *                  screen is far worse than a missed block, so a low-confidence
 *                  screen is always left alone.
 *  - [CONFIRMED] → the target's own stable indicators are present.
 */
enum class GuardConfidence {
    NONE,
    CANDIDATE,
    CONFIRMED,
}

/**
 * Which part of the target's flow the screen represents. Generic on purpose —
 * a rule may talk about a "list" or a "viewer" without naming any app.
 */
enum class GuardDestination {
    UNKNOWN,

    /** The tappable entry point inside an otherwise normal screen. */
    ENTRY_POINT,

    /** The target's own list / landing screen. */
    LIST,

    /** Full-screen content playback inside the target. */
    VIEWER,
}

/**
 * The decision ONE rule reached about ONE screen, plus the stable ids of the
 * indicators that produced it (used for controlled debug logging — indicator
 * ids are code constants, never user content).
 */
data class GuardVerdict(
    val targetId: String = "",
    val confidence: GuardConfidence = GuardConfidence.NONE,
    val destination: GuardDestination = GuardDestination.UNKNOWN,
    val signals: List<String> = emptyList(),
) {

    /** True only for a confirmed destination — the only case that may act. */
    val isConfirmed: Boolean get() = confidence == GuardConfidence.CONFIRMED

    /** True for a low-confidence look-alike that must be left alone. */
    val isCandidate: Boolean get() = confidence == GuardConfidence.CANDIDATE

    /** The primary (strongest) indicator id, or "" when nothing matched. */
    val primarySignal: String get() = signals.firstOrNull().orEmpty()

    companion object {
        /** "This screen is not a guard target." */
        val ALLOW = GuardVerdict()
    }
}

/**
 * One blocking target. A target is a single in-app surface (a feed, a Status
 * tab, a short-video viewer) inside ONE package — never a whole application.
 * Adding a new target means adding a rule, not a new blocking system.
 */
interface UiGuardRule {

    /** Stable identity of the target (e.g. "whatsapp_status"). */
    val targetId: String

    /** The package(s) this rule inspects. Anything else is ignored outright. */
    val packages: Set<String>

    /** Judges one screen. Must be pure and must never throw. */
    fun evaluate(snapshot: ScreenSnapshot): GuardVerdict
}
