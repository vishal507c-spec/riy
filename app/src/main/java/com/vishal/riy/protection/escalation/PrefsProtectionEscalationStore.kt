package com.vishal.riy.protection.escalation

import android.content.Context

/**
 * SharedPreferences-backed implementation of [ProtectionEscalationStore] — the
 * production counterpart of the unit-test [InMemoryEscalationStore].
 *
 * This is the SAME persistence technology the existing architecture already
 * uses for the protection session, the lock deadline and the event log
 * ([com.vishal.riy.protection.state.PrefsProtectionStateStore],
 * [com.vishal.riy.lock.PrefsLockStore],
 * [com.vishal.riy.protection.events.PrefsProtectionEventStore]): one file, one
 * key, (de)serialised by [EscalationStateSerialization].
 *
 * No second database, no second generic SharedPreferences system and no
 * competing source of truth is introduced. Escalation counting state is stored
 * separately from the protection session only because the two have different
 * lifetimes: a session ends when its 2-hour window expires, while qualifying
 * events must keep counting toward the escalation window (24 hours) even after
 * a session has lapsed.
 */
class PrefsProtectionEscalationStore(context: Context) : ProtectionEscalationStore {

    private val prefs = context.applicationContext
        .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    override fun load(): EscalationState =
        EscalationStateSerialization.decode(prefs.getString(KEY_STATE, null)) ?: EscalationState.NONE

    override fun save(state: EscalationState) {
        prefs.edit()
            .putString(KEY_STATE, EscalationStateSerialization.encode(state))
            .commit()
    }

    override fun clear() {
        prefs.edit().remove(KEY_STATE).commit()
    }

    private companion object {
        const val PREFS_NAME = "riy_escalation_state_prefs"
        const val KEY_STATE = "escalation_state_v1"
    }
}

/**
 * Delimited (de)serialisation of an [EscalationState], in the same style as
 * the existing [com.vishal.riy.protection.state.ProtectionSessionSerialization]
 * so an escalation record round-trips without a device and without any new
 * dependency.
 *
 * Layout:
 *
 *     policyVersion | timestamps
 *
 * The timestamp list comes LAST and is itself comma-delimited, so the numeric
 * [policyVersion] header can always be found before it.
 *
 * A record that cannot be parsed is dropped to [EscalationState.NONE] rather
 * than half-restored — the same deterministic-corruption rule the session and
 * lock serialisations already apply.
 */
object EscalationStateSerialization {

    private const val SEP = "|"
    private const val LIST_SEP = ","
    private const val FIELD_COUNT = 2

    fun encode(state: EscalationState): String = buildString {
        append(state.policyVersion).append(SEP)
        append(state.eventTimestamps.joinToString(LIST_SEP) { it.toString() })
    }

    fun decode(raw: String?): EscalationState? {
        if (raw.isNullOrBlank()) return null
        // A record missing its separator cannot hold a timestamp list at all,
        // so it is rejected rather than half-restored.
        if (!raw.contains(SEP)) return null
        val parts = raw.split(SEP, limit = FIELD_COUNT)
        if (parts.size != FIELD_COUNT) return null
        // Allow empty timestamp list (valid for a fresh state), but reject
        // non-empty lists with unparseable entries.
        return try {
            EscalationState(
                policyVersion = parts[FIELD_POLICY_VERSION].toInt(),
                eventTimestamps = if (parts[FIELD_TIMESTAMPS].isBlank())
                    emptyList()
                else parts[FIELD_TIMESTAMPS]
                    .split(LIST_SEP)
                    .filter { it.isNotBlank() }
                    .map { it.toLong() },
            )
        } catch (_: IllegalArgumentException) {
            null
        } catch (_: NumberFormatException) {
            null
        }
    }

    private const val FIELD_POLICY_VERSION = 0
    private const val FIELD_TIMESTAMPS = 1
}
