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
    fun `expired and unrefreshable token requires login`() {
        assertEquals(ENTER_SILENT, DriveSessionPolicy.decide(inputs(tokenOk = true)))
        assertEquals(SHOW_PICKER, DriveSessionPolicy.decide(inputs(tokenOk = false)))
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
        // Stored email but the platform session is gone (revoked/removed).
        assertEquals(
            SHOW_PICKER,
            DriveSessionPolicy.decide(inputs(platform = null, silentOk = false, tokenOk = false)),
        )
        // Stored email but platform holds a DIFFERENT account: never adopt blindly.
        assertEquals(
            SHOW_PICKER,
            DriveSessionPolicy.decide(inputs(platform = "other@example.com")),
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
