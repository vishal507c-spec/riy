package com.vishal.riy.tracker

/**
 * The daily tracker record.
 *
 * A tracker day is identified by its LOCAL CALENDAR DATE, stored as an epoch
 * day (days since 1970-01-01). Storing a civil date rather than a timestamp is
 * what makes the tracker survive timezones, DST changes and midnight rollover
 * without shifting or duplicating history: the moment it was recorded is kept
 * separately and is used only for auditing, never for identity.
 */
enum class TrackerStatus {
    /** Masturbation occurred on this date. */
    YES,

    /** Confirmed: did not occur on this date. */
    NO,

    /** Explicitly "not sure" — recorded, but never counted as a NO. */
    UNKNOWN;

    companion object {
        /**
         * Lenient parse used by deserialization.
         *
         * A blank/absent value returns null (genuinely not a status). Anything
         * else unrecognised degrades to [UNKNOWN] instead of throwing or being
         * dropped: a value written by a different version of the app must never
         * silently delete a day the user actually answered.
         */
        fun parse(raw: String?): TrackerStatus? {
            val key = raw?.trim()?.uppercase()?.replace(' ', '_') ?: return null
            if (key.isEmpty()) return null
            return when (key) {
                "YES", "Y", "TRUE", "1" -> YES
                "NO", "N", "FALSE", "0" -> NO
                else -> UNKNOWN
            }
        }
    }
}

/**
 * One calendar day of history.
 *
 * @param epochDay local calendar date, days since 1970-01-01. The identity of
 *                 the record: [id] is derived from it, so saving the same date
 *                 twice updates one row instead of creating a second one.
 * @param status   the answer. [TrackerStatus.UNKNOWN] is a real recorded answer
 *                 and is deliberately NOT the same as a missing record.
 * @param createdAt epoch millis when this date was first recorded.
 * @param updatedAt epoch millis of the last edit.
 */
data class TrackerDay(
    val epochDay: Long,
    val status: TrackerStatus,
    val createdAt: Long = 0L,
    val updatedAt: Long = 0L,
) {
    /** Stable identifier, derived only from the date. */
    val id: String get() = idFor(epochDay)

    /** True only for a confirmed answer; a NO streak must never count these. */
    val isConfirmedNo: Boolean get() = status == TrackerStatus.NO

    companion object {
        fun idFor(epochDay: Long): String = "day-$epochDay"

        /**
         * Creates a record, preserving [existing]'s creation time when this is an
         * edit of the same date. Multiple events on one date therefore collapse
         * into a single YES day rather than inflating the count.
         */
        fun upsert(
            epochDay: Long,
            status: TrackerStatus,
            nowMillis: Long,
            existing: TrackerDay?,
        ): TrackerDay = TrackerDay(
            epochDay = epochDay,
            status = status,
            createdAt = existing?.createdAt ?: nowMillis,
            updatedAt = nowMillis,
        )
    }
}