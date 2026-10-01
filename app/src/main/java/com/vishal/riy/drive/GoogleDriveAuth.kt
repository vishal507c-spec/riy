package com.vishal.riy.drive

import android.accounts.Account
import android.content.Context
import android.content.Intent
import com.google.android.gms.auth.GoogleAuthUtil
import com.google.android.gms.auth.api.signin.GoogleSignIn
import com.google.android.gms.auth.api.signin.GoogleSignInAccount
import com.google.android.gms.auth.api.signin.GoogleSignInOptions
import com.google.android.gms.common.api.ApiException
import com.google.android.gms.common.api.CommonStatusCodes
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

    /**
     * Requests ONLY what this app consumes: the account email (the Drive owner
     * identity) and the least-privilege Drive scope.
     *
     * Deliberately NOT seeded from [GoogleSignInOptions.DEFAULT_SIGN_IN]. That
     * constant is a full options object whose builder additionally called
     * `requestId()`, i.e. it asks for the `openid` scope and an ID TOKEN. An ID
     * token is minted for a Web-application OAuth client; this app registers an
     * ANDROID-type OAuth client only and never reads
     * [GoogleSignInAccount.idToken] (the access token comes from [accessToken]
     * via GoogleAuthUtil). Asking an Android client for an ID token is a
     * client-type mismatch that the auth backend rejects with
     * `CommonStatusCodes.DEVELOPER_ERROR` (10) right after the account is
     * picked — which is exactly the observed failure. Building the options
     * directly keeps the requested scope set to email + drive.file.
     */
    fun signInOptions(): GoogleSignInOptions = GoogleSignInOptions.Builder()
        .requestEmail()
        .requestScopes(Scope(SCOPE_FILE))
        .build()

    /**
     * Extracts the signed-in account from the sign-in result intent. A null
     * return means the sign-in did NOT succeed (user cancelled, developer
     * error, failed scope grant, …) — the failure class is logged (never any
     * token) so a dead sign-in loop is diagnosable instead of silent.
     */
    fun accountFromIntent(data: Intent?): GoogleSignInAccount? = try {
        data?.let { GoogleSignIn.getSignedInAccountFromIntent(it).result }
    } catch (e: Exception) {
        android.util.Log.w(
            "RiyDrive",
            "sign-in result carried no authenticated account: " + describeFailure(e),
        )
        null
    }

    /**
     * Full failure detail for a dead sign-in. Every cause in the chain is named
     * and its GMS status code is resolved to a readable status (e.g.
     * `10 (DEVELOPER_ERROR)`), because `ApiException.message` on its own only
     * renders as `"10:"` and hides the reason. Never logs a token or any
     * account credential — only class names, numeric status codes and the
     * exception's own message.
     */
    private fun describeFailure(error: Throwable): String =
        generateSequence(error) { it.cause }
            .take(6)
            .joinToString("  <-  ") { cause ->
                val status = (cause as? ApiException)?.statusCode
                val label = status?.let { "$it (${CommonStatusCodes.getStatusCodeString(it)})" } ?: "n/a"
                "${cause.javaClass.name}[status=$label]: ${cause.message}"
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
