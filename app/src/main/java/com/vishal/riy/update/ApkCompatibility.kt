package com.vishal.riy.update

import com.vishal.riy.R

/**
 * Everything that can be established about an APK **without installing it**, so a
 * bad update is rejected before Android is ever asked to act.
 */
data class ApkFacts(
    val packageName: String? = null,
    val versionCode: Long? = null,
    val versionName: String? = null,

    /** Lower-case hex SHA-256 of the signing certificate, or null if unknown. */
    val signerSha256: String? = null,

    /** The file exists and parsed as an Android package. */
    val readable: Boolean = false,
    val sizeBytes: Long = 0L,
) {
    companion object {
        /** "we could not even look at this file". */
        val UNREADABLE = ApkFacts(readable = false, sizeBytes = 0L)
    }
}

/** Why a candidate APK must not be installed. */
enum class ApkRejection {
    MISSING,
    CORRUPT,
    WRONG_PACKAGE,
    DOWNGRADE,
    SIGNATURE_MISMATCH,
}

/** What should happen with a candidate APK. */
enum class ApkDisposition {
    /** Legitimate in-place update. */
    INSTALLABLE,

    /** The very same version is already installed; nothing to do. */
    ALREADY_CURRENT,

    /** Must not be installed. [ApkAssessment.rejection] says why. */
    REJECTED,
}

/**
 * The verdict, the exact ADB command (when one is safe), and the sentence the
 * user is shown.
 */
data class ApkAssessment(
    val disposition: ApkDisposition,
    val rejection: ApkRejection? = null,
    val adbCommand: String? = null,
    val messageRes: Int = R.string.update_blocker_none,
)

/**
 * PURE compatibility policy for an update APK.
 *
 * It answers the four questions that decide whether `adb install -r` will be
 * accepted, so the app can explain the outcome *before* the user reaches a PC:
 *
 *  1. is the file a readable Android package at all?
 *  2. is it actually a RIY package? (never install an unrelated APK)
 *  3. is its signing certificate the SAME certificate the installed app uses?
 *     A mismatch is a hard stop: Android would reject it anyway, and the only
 *     "fix" would be an uninstall — which destroys the user's data. We never do
 *     that, and we never suggest bypassing the signature check.
 *  4. is it newer? (equal = nothing to do, older = a deliberate downgrade)
 *
 * Because the expected facts are injected as parameters, every case in the
 * edge-case list is a plain unit test.
 */
object ApkCompatibility {

    /** The package an update is ever allowed to touch. Fixed, not a parameter. */
    const val EXPECTED_PACKAGE = "com.vishal.riy"

    fun assess(installed: ApkFacts, candidate: ApkFacts, apkFileName: String): ApkAssessment {
        if (candidate.sizeBytes <= 0L) {
            return reject(ApkRejection.MISSING, R.string.update_reject_missing)
        }
        if (!candidate.readable || candidate.packageName.isNullOrBlank()) {
            return reject(ApkRejection.CORRUPT, R.string.update_reject_corrupt)
        }
        if (candidate.packageName != installed.packageName) {
            return reject(ApkRejection.WRONG_PACKAGE, R.string.update_reject_wrong_package)
        }
        // A certificate we could not read on EITHER side is never treated as a
        // match: an unverifiable signature must not become a silent pass.
        val installedSigner = installed.signerSha256
        val candidateSigner = candidate.signerSha256
        if (installedSigner == null || candidateSigner == null || installedSigner != candidateSigner) {
            return reject(ApkRejection.SIGNATURE_MISMATCH, R.string.update_reject_signature)
        }

        val installedCode = installed.versionCode
        val candidateCode = candidate.versionCode
        if (installedCode != null && candidateCode != null) {
            if (candidateCode == installedCode) {
                return ApkAssessment(
                    disposition = ApkDisposition.ALREADY_CURRENT,
                    messageRes = R.string.update_reject_same_version,
                )
            }
            if (candidateCode < installedCode) {
                // An older build is rejected rather than offered. A `-d`
                // downgrade command is deliberately not generated: it could be
                // copied accidentally, and downgrading under policy restrictions
                // deserves an explicit choice, not an irreversible side effect.
                return reject(ApkRejection.DOWNGRADE, R.string.update_reject_downgrade)
            }
        }

        return ApkAssessment(
            disposition = ApkDisposition.INSTALLABLE,
            adbCommand = installCommand(apkFileName),
            messageRes = R.string.update_ready_usb,
        )
    }

    /** The ordinary, safe, in-place update command. */
    fun installCommand(apkFileName: String): String = "adb install -r \"$apkFileName\""

    /** Pull command for the APK exported to the phone's Downloads folder. */
    fun pullCommand(exportedName: String, localFileName: String): String =
        "adb pull \"/sdcard/Download/$exportedName\" \"$localFileName\""

    private fun reject(rejection: ApkRejection, messageRes: Int) = ApkAssessment(
        disposition = ApkDisposition.REJECTED,
        rejection = rejection,
        // A rejected APK NEVER gets a command. Silently handing the user an
        // `adb uninstall` would be the data-destroying "workaround".
        adbCommand = null,
        messageRes = messageRes,
    )
}