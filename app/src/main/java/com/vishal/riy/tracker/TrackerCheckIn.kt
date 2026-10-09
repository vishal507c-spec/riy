package com.vishal.riy.tracker

/**
 * Decides WHICH date a check-in is about and whether one is still pending.
 *
 * The default question is about YESTERDAY, because yesterday is the most recent
 * COMPLETED calendar day — today is still in progress, so asking about it
 * would be asking the user to predict their own future.
 *
 * The date is always carried explicitly by the caller and shown to the user.
 * Nothing here ever infers the target date from "now" at save time, which is
 * exactly how an answer for yesterday ends up filed under today.
 */
object TrackerCheckIn {

    /**
     * The date the morning check-in should ask about, or null when there is
     * nothing pending.
     *
     * @param todayEpochDay the current local calendar day.
     * @param hasRecord     whether a given epoch day already has a record.
     *
     * Pending only while YESTERDAY is unanswered. Once yesterday is answered
     * the prompt stops, even if older days are still blank: those remain
     * visible as "not recorded" in the calendar and can be filled in by hand.
     * That is deliberate — repeatedly popping up for each missed day would be
     * intrusive, and silently back-filling them as NO would be dishonest.
     */
    fun pendingDate(todayEpochDay: Long, hasRecord: (Long) -> Boolean): Long? {
        val yesterday = TrackerDates.addDays(todayEpochDay, -1L)
        // Never ask about a date before the epoch itself (defensive).
        if (yesterday < 0L) return null
        return if (hasRecord(yesterday)) null else yesterday
    }

    /**
     * Whether the automatic in-app prompt may be shown right now.
     *
     * [alreadyPromptedToday] is the once-per-day guard: the prompt appears on the
     * first app use of the morning and never re-appears intrusively the same day.
     */
    fun shouldAutoPrompt(
        pendingDay: Long?,
        alreadyPromptedToday: Boolean,
    ): Boolean = pendingDay != null && !alreadyPromptedToday

    /** The title line of the check-in, e.g. "Good Morning!". */
    fun greeting(nowMillis: Long): String {
        val hour = java.util.Calendar.getInstance().get(java.util.Calendar.HOUR_OF_DAY)
        return when (hour) {
            in 5..11 -> "Good Morning!"
            in 12..16 -> "Good Afternoon!"
            in 17..21 -> "Good Evening!"
            else -> "Good Night!"
        }
    }
}