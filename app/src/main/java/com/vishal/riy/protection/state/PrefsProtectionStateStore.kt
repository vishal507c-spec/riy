package com.vishal.riy.protection.state

import android.content.Context
import com.vishal.riy.protection.policy.ProtectionState

/**
 * SharedPreferences-backed implementation of [ProtectionStateStore] — the
 * production counterpart of the unit-test [InMemoryProtectionStateStore].
 *
 * This is the SAME persistence technology the existing architecture already
 * uses for the lock deadline ([com.vishal.riy.lock.PrefsLockStore]) and the
 * blocker's own state: one file, one key, (de)serialised by
 * [ProtectionSessionSerialization]. No second database, no second deadline
 * store and no competing source of truth is introduced — see the note in
 * [ProtectionSession] about which deadline is authoritative.
 */
class PrefsProtectionStateStore(context: Context) : ProtectionStateStore {

    private val prefs = context.applicationContext
        .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    override fun load(): ProtectionSession? =
        ProtectionSessionSerialization.decode(prefs.getString(KEY_SESSION, null))

    override fun save(session: ProtectionSession) {
        prefs.edit()
            .putString(KEY_SESSION, ProtectionSessionSerialization.encode(session))
            .apply()
    }

    override fun clear() {
        prefs.edit().remove(KEY_SESSION).apply()
    }

    /** Same semantics as the contract: the persisted view of [load]. */
    override fun current(): ProtectionSession? = load()

    private companion object {
        const val PREFS_NAME = "riy_protection_state_prefs"
        const val KEY_SESSION = "protection_session_v1"
    }
}

/**
 * Delimited (de)serialisation of a [ProtectionSession], mirroring the existing
 * [com.vishal.riy.lock.LockStateSerialization] so a session round-trips without
 * a device and without any new dependency.
 *
 * Layout (fields 1-6 and 8 are scalars; field 7 is the package list, and it is
 * kept NEXT TO LAST so the numeric [blockedCount] trailer can always be found):
 *
 *     sessionId | state | startTime | expiryTime | reason | policyVersion
 *               | allowedPackages | blockedCount
 *
 * Fields are written with [sanitize] so a value containing a separator can
 * never shift another field — the same defence [com.vishal.riy.lock.LockStateSerialization]
 * uses for the domain.
 */
object ProtectionSessionSerialization {

    private const val SEP = "|"
    private const val LIST_SEP = ","
    private const val FIELD_COUNT = 8

    fun encode(session: ProtectionSession): String = buildString {
        append(sanitize(session.sessionId)).append(SEP)
        append(session.state.name).append(SEP)
        append(session.startTime).append(SEP)
        append(session.expiryTime).append(SEP)
        append(sanitize(session.reason)).append(SEP)
        append(session.policyVersion).append(SEP)
        append(session.allowedPackages.joinToString(LIST_SEP) { sanitize(it) }).append(SEP)
        append(session.blockedCount)
    }

    fun decode(raw: String?): ProtectionSession? {
        if (raw.isNullOrBlank()) return null
        val parts = raw.split(SEP, limit = FIELD_COUNT)
        if (parts.size != FIELD_COUNT) return null
        return try {
            ProtectionSession(
                sessionId = parts[FIELD_SESSION_ID],
                state = ProtectionState.valueOf(parts[FIELD_STATE]),
                startTime = parts[FIELD_START].toLong(),
                expiryTime = parts[FIELD_EXPIRY].toLong(),
                reason = parts[FIELD_REASON],
                policyVersion = parts[FIELD_POLICY_VERSION].toInt(),
                allowedPackages = parts[FIELD_ALLOWED]
                    .split(LIST_SEP)
                    .filter { it.isNotBlank() },
                blockedCount = parts[FIELD_BLOCKED_COUNT].toInt(),
            )
        } catch (_: IllegalArgumentException) {
            // A state name that is no longer in the enum, or a non-numeric
            // scalar: the record is uninterpretable, so it is dropped rather
            // than half-restored.
            null
        } catch (_: NumberFormatException) {
            null
        }
    }

    /** Strips the delimiters so no field can ever absorb another. */
    private fun sanitize(value: String): String =
        value.replace(SEP, "").replace(LIST_SEP, "")

    private const val FIELD_SESSION_ID = 0
    private const val FIELD_STATE = 1
    private const val FIELD_START = 2
    private const val FIELD_EXPIRY = 3
    private const val FIELD_REASON = 4
    private const val FIELD_POLICY_VERSION = 5
    private const val FIELD_ALLOWED = 6
    private const val FIELD_BLOCKED_COUNT = 7
}
