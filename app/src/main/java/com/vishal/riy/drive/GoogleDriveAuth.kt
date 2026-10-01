package com.vishal.riy.drive

import android.accounts.Account
import android.content.Context
import android.content.Intent
import com.google.android.gms.auth.GoogleAuthUtil
import com.google.android.gms.auth.api.signin.GoogleSignIn
import com.google.android.gms.auth.api.signin.GoogleSignInAccount
import com.google.android.gms.auth.api.signin.GoogleSignInOptions
import com.google.android.gms.common.api.Scope

/**
 * Secretless Android OAuth for Google Drive. The OAuth client is keyed by
 * package name + SHA-1 in Google Cloud (no client secret on Android). We only
 * need an access token from a user-approved account, which we obtain via
 * GoogleAuthUtil (automatically refreshed by the platform).
 *
 * Cloned from the reference `GoogleDriveAuth` (flow identical).
 * Tokens live only in this layer — never in a snapshot, never in logs.
 */
object GoogleDriveAuth {

    /** Drive scoped to app-created files (recommended, least privilege). */
    const val SCOPE_FILE = "https://www.googleapis.com/auth/drive.file"

    fun signInOptions(): GoogleSignInOptions = GoogleSignInOptions.Builder(GoogleSignInOptions.DEFAULT_SIGN_IN)
        .requestEmail()
        .requestScopes(Scope(SCOPE_FILE))
        .build()

    fun accountFromIntent(data: Intent?): GoogleSignInAccount? = try {
        data?.let { GoogleSignIn.getSignedInAccountFromIntent(it).result }
    } catch (_: Exception) {
        null
    }

    /**
     * The platform's currently signed-in account for THESE sign-in options, or
     * null when nobody is signed in. Synchronous, no UI, never throws. This is
     * the first silent-session check: a returning user is recognised here
     * WITHOUT any account picker.
     */
    fun lastSignedIn(context: Context): GoogleSignInAccount? = try {
        GoogleSignIn.getLastSignedInAccount(context.applicationContext)
    } catch (_: Exception) {
        null
    }

    /**
     * Drops one rejected access token from the platform cache so the next
     * [accessToken] call mints a fresh one (the standard expired-token
     * recovery: 401 → clearToken(rejected) → retry once). Never throws.
     */
    fun invalidateToken(context: Context, token: String) {
        runCatching { GoogleAuthUtil.clearToken(context.applicationContext, token) }
    }

    /** Google-account handle used by GoogleAuthUtil. */
    fun toAndroidAccount(email: String): Account = Account(email, "com.google")

    fun accessToken(context: Context, email: String): String =
        GoogleAuthUtil.getToken(context.applicationContext, toAndroidAccount(email), "oauth2:$SCOPE_FILE")
}
