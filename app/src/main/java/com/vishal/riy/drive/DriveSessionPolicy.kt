package com.vishal.riy.drive

/**
 * Pure session-state machine for the Drive login flow: given the facts about
 * the persisted session and the platform Google session, decides whether the
 * app may enter silently or must show the interactive account picker.
 *
 * Rules (each maps to a required test case):
 *  - Explicit logout always forces the picker (case G).
 *  - Stored email + matching platform account + obtainable token → silent
 *    entry (cases B, C, D, E). `GoogleAuthUtil` refreshes an expired access
 *    token behind the scenes, so a refreshable expiry also enters silently
 *    (case F).
 *  - No stored email but a successful platform silent sign-in + token →
 *    silent entry and adoption (fresh install on a device whose Google
 *    account already granted access; case A inverted).
 *  - Anything else — no session, mismatch, revoked/removed account, silent
 *    failure — shows the picker (case H).
 *
 * Pure JVM: the whole matrix is unit-testable without Android or Google APIs.
 */
object DriveSessionPolicy {

    /** Facts about the current session state. All nullable/booleans, no Android types. */
    data class SessionInputs(
        val storedEmail: String?,
        val platformEmail: String?,
        val silentSignInOk: Boolean,
        val tokenOk: Boolean,
        val userLoggedOut: Boolean,
    )

    enum class SessionAction {
        /** Enter the app directly; no account picker. */
        ENTER_SILENT,

        /** Interactive Google account picker is genuinely required. */
        SHOW_PICKER,
    }

    fun decide(i: SessionInputs): SessionAction {
        if (i.userLoggedOut) return SessionAction.SHOW_PICKER
        val stored = i.storedEmail?.trim()?.takeIf { it.isNotEmpty() }
        val platform = i.platformEmail?.trim()?.takeIf { it.isNotEmpty() }
        // Returning user: persisted session + live platform session for the
        // SAME account + a provably obtainable token.
        if (stored != null && platform != null &&
            stored.equals(platform, ignoreCase = true) && i.tokenOk
        ) {
            return SessionAction.ENTER_SILENT
        }
        // Adoptable platform session (e.g. reinstall where Google still holds
        // the grant): silent sign-in succeeded and a token is obtainable.
        if (stored == null && i.silentSignInOk && i.tokenOk) {
            return SessionAction.ENTER_SILENT
        }
        return SessionAction.SHOW_PICKER
    }
}
