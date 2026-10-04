package com.vishal.riy.guard

import android.util.Log
import com.vishal.riy.BuildConfig

/**
 * Controlled, privacy-safe debug logging for the UI guard.
 *
 * THIS FILE EXISTS ONLY IN THE DEBUG VARIANT. The release variant compiles a
 * no-op twin with the same API, so none of these strings — or the code that
 * reads them — ships to users at all.
 *
 * RULES:
 *  - the vocabulary is exactly the four lines the feature is specified to emit:
 *    "WhatsApp detected", "Status candidate detected", "Status blocked" and
 *    "Normal WhatsApp screen allowed";
 *  - only the target id, the stable indicator ids and the chosen action are
 *    ever logged. NO message text, NO contact name, NO chat content, NO node
 *    content and nothing else about the user's data is ever written to the log;
 *  - every line is additionally gated on [BuildConfig.DEBUG] so a debug-signed
 *    release candidate cannot log either.
 */
object UiGuardLog {

    private const val TAG = "RiyUiGuard"

    const val WHATSAPP_DETECTED = "WhatsApp detected"
    const val STATUS_CANDIDATE = "Status candidate detected"
    const val STATUS_BLOCKED = "Status blocked"
    const val SCREEN_ALLOWED = "Normal WhatsApp screen allowed"

    /** [detail] must be built from constants, never from user content. */
    fun d(line: String, detail: String? = null) {
        if (!BuildConfig.DEBUG) return
        if (detail.isNullOrEmpty()) Log.d(TAG, line) else Log.d(TAG, "$line — $detail")
    }

    /** Transition trace: indicator ids plus the action the policy chose. */
    fun decision(line: String, verdict: GuardVerdict, step: GuardStep) {
        if (!BuildConfig.DEBUG) return
        val destination = if (verdict.destination.name == "UNKNOWN") "-" else verdict.destination.name
        Log.d(
            TAG,
            "$line — target=${verdict.targetId.ifEmpty { "-" }} " +
                "confidence=${verdict.confidence.name} destination=$destination " +
                "signals=${verdict.signals.joinToString(",").ifEmpty { "-" }} " +
                "action=${step.name}",
        )
    }
}
