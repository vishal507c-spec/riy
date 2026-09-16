package com.vishal.riy.awareness

/**
 * All awareness copy in one place. Language is deliberately simple, calm and
 * non-judgmental: no guilt, no shame, no fear, no insult, and no clinical
 * diagnosis. The goal is pause -> observation -> choice, never punishment.
 */
object AwarenessMessages {

    /** Stage 1 of the pause (always the same calm opening). */
    const val OPENING_TITLE = "Ruko."
    const val OPENING_BODY =
        "Urge aa rahi hai.\nTumhe abhi us par action lene ki zaroorat nahi hai."

    /** Stage 2 of the pause. */
    const val OBSERVE_TITLE = "Bas observe karo."
    const val OBSERVE_BODY =
        "Saans ko notice karo.\nUrge ko aane-jaane do."

    /** Rotating short messages shown through the rest of the pause. */
    val ROTATING = listOf(
        "Urge tum nahi ho. Use bas dekho.",
        "Abhi kuch karna zaroori nahi hai.",
        "Ye ek wave hai. Aayegi aur chali jayegi.",
        "Reaction se pehle awareness.",
        "Bas dekho. Decide baad mein karna.",
        "Mind kuch chahta hai. Tumhe turant follow karna zaroori nahi.",
    )

    /** Shown when the lock screen is active. */
    const val LOCK_TITLE = "Abhi browsing locked hai."
    const val LOCK_HINT = "Is waqt bas apne mind ko observe karo."

    /**
     * Repeated slips are never shamed; the tone stays curious and kind
     * (see the spec's slip-handling section).
     */
    const val SLIP_TITLE = "Koi punishment nahi."
    const val SLIP_BODY =
        "Bas dekho kya hua.\nPhir se aware ho jao."
}
