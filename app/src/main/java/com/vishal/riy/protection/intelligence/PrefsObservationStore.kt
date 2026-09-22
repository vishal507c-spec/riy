package com.vishal.riy.protection.intelligence

import android.content.Context
import com.vishal.riy.blocker.BlocklistMatch

/**
 * SharedPreferences-backed implementation of [ObservationStore] — the production
 * counterpart of the unit-test [InMemoryObservationStore].
 *
 * This is the SAME persistence technology the existing architecture already
 * uses for the lock deadline ([com.vishal.riy.lock.PrefsLockStore]), the
 * protection session ([com.vishal.riy.protection.state.PrefsProtectionStateStore])
 * and the escalation count
 * ([com.vishal.riy.protection.escalation.PrefsProtectionEscalationStore]): one
 * file, one key, delimited (de)serialised so a value containing a separator can
 * never shift another field. No second database and no competing source of
 * truth is introduced.
 *
 * BOUND AND EXPIRED. The store keeps at most [ProtectionCorrelationWindow.MAX_RETAINED]
 * records and drops anything older than the detection window at READ time, so
 * it is a bounded record of the recent past rather than an growing history.
 */
class PrefsObservationStore(
    context: Context,
    private val clock: () -> Long = System::currentTimeMillis,
) : ObservationStore {

    private val prefs = context.applicationContext
        .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    override fun load(): List<SignalObservation> {
        val raw = prefs.getStringSet(KEY_OBSERVATIONS, null) ?: return emptyList()
        val parsed = raw.mapNotNull(ObservationSerialization::decode)
        val retained = parsed
            .filter { clock() - it.timestamp <= ProtectionCorrelationWindow.CORRELATION_WINDOW_MS }
            .sortedBy { it.timestamp }
            .takeLast(ProtectionCorrelationWindow.MAX_RETAINED)
        if (retained.size < parsed.size) persist(retained)
        return retained
    }

    override fun append(observation: SignalObservation): Boolean {
        val current = load().toMutableList()

        // The store's own dedup: the same (domain, class) inside the dedup
        // window is one observation, not two — a page load fires A, AAAA and
        // retries for the same host in rapid succession. The EARLIEST timestamp
        // is kept so a later genuine repeat still registers as a repeat.
        if (current.any { existing ->
                existing.domain == observation.domain &&
                    existing.matchClass == observation.matchClass &&
                    observation.timestamp - existing.timestamp <
                    ProtectionCorrelationWindow.SAME_DOMAIN_REPEAT_MS
            }
        ) {
            return false
        }

        current += observation
        persist(current.takeLast(ProtectionCorrelationWindow.MAX_RETAINED))
        return true
    }

    override fun clear() {
        prefs.edit().remove(KEY_OBSERVATIONS).apply()
    }

    private fun persist(observations: List<SignalObservation>) {
        prefs.edit()
            .putStringSet(
                KEY_OBSERVATIONS,
                observations.map(ObservationSerialization::encode).toSet(),
            )
            .apply()
    }

    private companion object {
        const val PREFS_NAME = "riy_observation_prefs"
        const val KEY_OBSERVATIONS = "observations_v1"
    }
}

/**
 * Delimited (de)serialisation of a [SignalObservation], mirroring the existing
 * [com.vishal.riy.lock.LockStateSerialization] style so an observation
 * round-trips without a device and without any new dependency.
 *
 * Layout: domain | matchClass | timestamp | policyVersion
 *
 * The domain is sanitised (it is external input from a DNS question) so a '|'
 * inside it could never shift the numeric fields — the same defence
 * [com.vishal.riy.lock.LockStateSerialization] uses.
 */
object ObservationSerialization {

    private const val SEP = "|"
    private const val FIELD_COUNT = 4

    private const val FIELD_DOMAIN = 0
    private const val FIELD_CLASS = 1
    private const val FIELD_TIMESTAMP = 2
    private const val FIELD_POLICY = 3

    fun encode(observation: SignalObservation): String = buildString {
        append(sanitize(observation.domain)).append(SEP)
        append(encodeClass(observation.matchClass)).append(SEP)
        append(observation.timestamp).append(SEP)
        append(observation.policyVersion)
    }

    fun decode(raw: String?): SignalObservation? {
        if (raw.isNullOrBlank()) return null
        val parts = raw.split(SEP, limit = FIELD_COUNT)
        if (parts.size != FIELD_COUNT) return null
        return try {
            SignalObservation(
                domain = parts[FIELD_DOMAIN],
                matchClass = decodeClass(parts[FIELD_CLASS]) ?: return null,
                timestamp = parts[FIELD_TIMESTAMP].toLong(),
                policyVersion = parts[FIELD_POLICY].toInt(),
            )
        } catch (_: NumberFormatException) {
            null
        }
    }

    private fun encodeClass(matchClass: BlocklistMatch): String = when (matchClass) {
        BlocklistMatch.DEFINITIVE -> "D"
        BlocklistMatch.SUSPECT -> "S"
    }

    private fun decodeClass(token: String): BlocklistMatch? = when (token) {
        "D" -> BlocklistMatch.DEFINITIVE
        "S" -> BlocklistMatch.SUSPECT
        else -> null
    }

    /** Strips the delimiter so no field can ever absorb another. */
    private fun sanitize(value: String): String = value.replace(SEP, "")
}
