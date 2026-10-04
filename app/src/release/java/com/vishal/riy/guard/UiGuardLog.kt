package com.vishal.riy.guard

/**
 * The RELEASE twin of the debug-only [UiGuardLog].
 *
 * Same API, zero behaviour: no log call is emitted, no Logcat line is ever
 * produced, and — because every constant below is inlined as an empty string
 * at each call site — not even the wording of the four development log lines
 * exists in the shipped APK.
 *
 * Keeping the twin in the release source set (rather than behind an `if`) is
 * what makes the removal real without turning on shrinking for the whole app.
 */
object UiGuardLog {

    const val WHATSAPP_DETECTED = ""
    const val STATUS_CANDIDATE = ""
    const val STATUS_BLOCKED = ""
    const val SCREEN_ALLOWED = ""

    fun d(line: String, detail: String? = null) = Unit

    fun decision(line: String, verdict: GuardVerdict, step: GuardStep) = Unit
}
