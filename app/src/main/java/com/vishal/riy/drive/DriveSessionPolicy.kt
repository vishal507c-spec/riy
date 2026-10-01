package com.vishal.riy.drive

/**
 * Pure session-state machine for the Drive login flow: given the facts about
 * the persisted session and the live OAuth grant, decides whether the app may
 * enter silently or must show the interactive account picker.
 *
 * The design rule is that [SessionInputs.tokenOk] — "a real access token is
 * obtainable for the stored account right now" — is the ONLY proof of
 * authentication. The stored email is a *pointer* to which account to ask
 * about, never evidence that this user is signed in.
 *
 * [SessionInputs.platformEmail] is deliberately NOT a gate. It is the
 * GoogleSignIn cache, which is a different store from the OAuth grant and is
 * routinely empty after a process kill, an app update or Play Services cache
 * eviction. Treating a cold cache as "logged out" is what sent returning users
 * back to the account picker even though their Drive grant was still valid.
 *
 * Rules:
 *  - Explicit logout always forces the picker, even if leftovers exist.
 *  - Stored account + a genuinely obtainable token → silent entry. This covers
 *    reopen, force-stop, app update and token expiry (getToken refreshes
 *    behind the scenes), and it deliberately ignores the cache.
 *  - No stored account, but the platform can silently authenticate the same
 *    grant and a token follows → adopt silently (reinstall / dropped storage).
 *  - Stored account whose grant is genuinely gone (revoked, account removed,
 *    Drive access removed from the Google account) → picker.
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
        // An explicit logout outranks every other fact, including a grant that
        // the platform may still be holding.
        if (i.userLoggedOut) return SessionAction.SHOW_PICKER
        val stored = i.storedEmail?.trim()?.takeIf { it.isNotEmpty() }
        if (stored == null) {
            // Nothing remembered. The only silent way in is a platform grant we
            // can adopt AND actually use (a token must follow).
            return if (i.silentSignInOk && i.tokenOk) {
                SessionAction.ENTER_SILENT
            } else {
                SessionAction.SHOW_PICKER
            }
        }
        // Remembered account: a live token for THAT account is the whole proof.
        // The GoogleSignIn cache is intentionally not consulted — see the note
        // on platformEmail above.
        return if (i.tokenOk) {
            SessionAction.ENTER_SILENT
        } else {
            SessionAction.SHOW_PICKER
        }
    }
}
