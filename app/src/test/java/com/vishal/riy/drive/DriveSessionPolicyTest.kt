package com.vishal.riy.drive

import com.vishal.riy.drive.DriveSessionPolicy.SessionAction.ENTER_SILENT
import com.vishal.riy.drive.DriveSessionPolicy.SessionAction.SHOW_PICKER
import com.vishal.riy.drive.DriveSessionPolicy.SessionInputs
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Session-persistence matrix: when the app may enter silently vs when the
 * Google account picker is genuinely required. Mirrors the required cases.
 */
class DriveSessionPolicyTest {

    private fun inputs(
        stored: String? = "user@example.com",
        platform: String? = "user@example.com",
        silentOk: Boolean = true,
        tokenOk: Boolean = true,
        loggedOut: Boolean = false,
    ) = SessionInputs(
        storedEmail = stored,
        platformEmail = platform,
        silentSignInOk = silentOk,
        tokenOk = tokenOk,
        userLoggedOut = loggedOut,
    )

    @Test
    fun `A fresh install with no session requires login`() {
        // No stored email, no platform grant, nothing silent to restore.
        assertEquals(
            SHOW_PICKER,
            DriveSessionPolicy.decide(inputs(stored = null, platform = null, silentOk = false, tokenOk = false)),
        )
    }

    @Test
    fun `B C D reopen and force-stop with valid session enter silently`() {
        assertEquals(ENTER_SILENT, DriveSessionPolicy.decide(inputs()))
    }

    @Test
    fun `E valid token session enters directly`() {
        assertEquals(
            ENTER_SILENT,
            DriveSessionPolicy.decide(inputs(stored = "user@example.com", platform = "user@example.com", tokenOk = true)),
        )
    }

    @Test
    fun `F expired but refreshable token enters silently`() {
        // GoogleAuthUtil transparently refreshes behind getToken: a token that
        // is obtainable after refresh still counts as tokenOk.
        assertEquals(ENTER_SILENT, DriveSessionPolicy.decide(inputs(tokenOk = true)))
    }

    @Test
    fun `expired but unrefreshable token requires login`() {
        // getToken refreshes transparently, so an expiry is still tokenOk=true
        // and enters silently (requirement 7). Only a genuinely dead grant
        // (tokenOk=false) escalates to the picker.
        assertEquals(ENTER_SILENT, DriveSessionPolicy.decide(inputs(tokenOk = true)))
        assertEquals(SHOW_PICKER, DriveSessionPolicy.decide(inputs(tokenOk = false)))
    }

    @Test
    fun `logged out flag wins over a stored account and a live token`() {
        // Requirement 8: after an explicit logout nothing may be adopted, even
        // if the account is still remembered and its grant still works.
        assertEquals(
            SHOW_PICKER,
            DriveSessionPolicy.decide(inputs(tokenOk = true, loggedOut = true)),
        )
    }

    @Test
    fun `G explicit logout requires login even with leftovers`() {
        // Even if a platform session lingers, an explicit logout wins.
        assertEquals(SHOW_PICKER, DriveSessionPolicy.decide(inputs(loggedOut = true)))
        assertEquals(
            SHOW_PICKER,
            DriveSessionPolicy.decide(inputs(platform = "user@example.com", loggedOut = true)),
        )
    }

    @Test
    fun `H revoked or removed account requires login`() {
        // Stored email but the grant is genuinely gone (revoked/removed):
        // no token can be obtained, so login is required.
        assertEquals(
            SHOW_PICKER,
            DriveSessionPolicy.decide(inputs(platform = null, silentOk = false, tokenOk = false)),
        )
    }

    @Test
    fun `a cold GoogleSignIn cache must not send a returning user back to the picker`() {
        // REGRESSION: the cache lives in a different store from the OAuth grant
        // and is routinely empty after a process kill, an app update or Play
        // Services cache eviction. A valid token for the stored account is the
        // real proof, so a null cache must still enter silently.
        assertEquals(
            ENTER_SILENT,
            DriveSessionPolicy.decide(
                inputs(stored = "user@example.com", platform = null, tokenOk = true),
            ),
        )
    }

    @Test
    fun `a stale cache naming a different account does not revoke a live grant`() {
        // The cache is a hint only. A usable token for the STORED account is
        // what matters, so a cache pointing elsewhere must not escalate to a
        // picker — that would strand a user whose Drive grant is fine.
        assertEquals(
            ENTER_SILENT,
            DriveSessionPolicy.decide(
                inputs(stored = "user@example.com", platform = "other@example.com", tokenOk = true),
            ),
        )
    }

    @Test
    fun `stored email without any token is never treated as authenticated`() {
        // Anti-faking: the pointer alone must not open the app. Without a token
        // the grant is not proven, so the picker is required.
        assertEquals(
            SHOW_PICKER,
            DriveSessionPolicy.decide(inputs(stored = "user@example.com", tokenOk = false)),
        )
    }

    @Test
    fun `silent sign-in without a usable token does not enter silently`() {
        // Adoption must also be proven by a token, never by the sign-in task
        // result alone.
        assertEquals(
            SHOW_PICKER,
            DriveSessionPolicy.decide(
                inputs(stored = null, platform = null, silentOk = true, tokenOk = false),
            ),
        )
    }

    @Test
    fun `adoptable platform session enters silently without stored email`() {
        // Reinstall where Google still holds the grant: silent sign-in + token.
        assertEquals(
            ENTER_SILENT,
            DriveSessionPolicy.decide(inputs(stored = null, platform = null, silentOk = true, tokenOk = true)),
        )
    }

    @Test
    fun `email alone without a token is never proof of authentication`() {
        // Stored + matching platform account but NO obtainable token → picker.
        assertEquals(
            SHOW_PICKER,
            DriveSessionPolicy.decide(inputs(tokenOk = false, silentOk = false)),
        )
    }

    @Test
    fun `blank emails behave as absent`() {
        // Blanks are absence: with nothing silent to adopt, the picker shows.
        assertEquals(
            SHOW_PICKER,
            DriveSessionPolicy.decide(
                inputs(stored = "  ", platform = "  ", silentOk = false, tokenOk = false),
            ),
        )
    }

    @Test
    fun `account matching ignores case and whitespace`() {
        assertEquals(
            ENTER_SILENT,
            DriveSessionPolicy.decide(inputs(stored = "User@Example.com ", platform = "user@example.com")),
        )
    }
}
