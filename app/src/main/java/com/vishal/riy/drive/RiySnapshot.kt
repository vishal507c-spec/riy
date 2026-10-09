package com.vishal.riy.drive

import org.json.JSONArray
import org.json.JSONObject

/**
 * The canonical riy snapshot — ONE JSON document capturing every locally
 * persisted protection store, bound by SHA-256. It is the riy equivalent of
 * the reference system's canonical SQLite VAULT: a single content-addressed
 * unit whose identity is its hash, never its filename.
 *
 * Covered stores (SharedPreferences file → dumped wholesale via `all`):
 *  - blocker_state_prefs        (VPN intent)
 *  - riy_lock_prefs             (2-hour deadline — sole authority stays LockEngine)
 *  - riy_protection_state_prefs (protection session mirror)
 *  - riy_protection_events_prefs (event log ring buffer)
 *  - riy_escalation_state_prefs (escalation counting)
 *  - riy_observation_prefs      (intelligence observations)
 *  - riy_tracker_prefs          (daily tracker YES/NO/UNKNOWN history)
 *
 * Encoding is deterministic (sorted store names, sorted keys, sorted string
 * sets, type-tagged values) so identical local state always yields identical
 * bytes → uploads are idempotent with zero writes on no-change.
 *
 * SCHEMA COMPATIBILITY: [FORMAT_VERSION] is deliberately NOT bumped for the
 * addition of riy_tracker_prefs. A store that is absent simply contributes no
 * object to the document, and [parse] ignores unknown store names — so a VAULT
 * written before the tracker existed restores normally, and a VAULT containing
 * tracker records restores into an older app that ignores them. Both
 * directions are lossless for the data each version actually owns.
 *
 * Pure JVM: no Android import, fully unit-testable.
 */
object RiySnapshot {

    const val FORMAT_VERSION = 1

    /**
     * Exact SharedPreferences file names snapshotted, in canonical (alphabetical)
     * order. The order is part of the byte-level contract: the snapshot is
     * content-addressed by SHA-256, so this list must stay sorted and only ever
     * gain APPENDED entries, or every existing VAULT would appear to change.
     */
    val STORE_FILES: List<String> = listOf(
        "blocker_state_prefs",
        "riy_escalation_state_prefs",
        "riy_lock_prefs",
        "riy_observation_prefs",
        "riy_protection_events_prefs",
        "riy_protection_state_prefs",
        "riy_tracker_prefs",
    )

    /**
     * One decoded snapshot. [stores] maps prefs-file → key → typed value
     * ([SnapValue]); [generation] is the monotonic snapshot counter used for
     * anti-rollback; [payloadSha256] is the SHA-256 of the exact canonical
     * bytes this was decoded from (empty when built locally, filled by [parse]).
     */
    data class Decoded(
        val snapshotId: String,
        val generation: Long,
        val createdAt: Long,
        val appVersion: String,
        val stores: Map<String, Map<String, SnapValue>>,
        val payloadSha256: String = "",
    )

    /** A type-tagged preference value (round-trips SharedPreferences exactly). */
    data class SnapValue(val type: Char, val raw: String) {
        companion object {
            @Suppress("UNCHECKED_CAST")
            fun of(value: Any?): SnapValue? = when (value) {
                is String -> SnapValue('s', value)
                is Int -> SnapValue('i', value.toString())
                is Long -> SnapValue('l', value.toString())
                is Float -> SnapValue('f', value.toString())
                is Boolean -> SnapValue('b', value.toString())
                is Set<*> -> SnapValue(
                    'e',
                    (value as Set<String>).sorted().joinToString("\u0000"),
                )
                else -> null // nulls and unknown types are never persisted
            }
        }

        /** Restores the original SharedPreferences value. */
        fun toValue(): Any = when (type) {
            'i' -> raw.toInt()
            'l' -> raw.toLong()
            'f' -> raw.toFloat()
            'b' -> raw.toBoolean()
            'e' -> if (raw.isEmpty()) emptySet<String>() else raw.split("\u0000").toSet()
            else -> raw
        }
    }

    /**
     * Builds canonical snapshot bytes. [stores] maps prefs-file → the raw
     * `SharedPreferences.all` map. Unknown files and unrepresentable values
     * are skipped (never fail a snapshot on an unexpected value).
     */
    fun build(
        stores: Map<String, Map<String, *>>,
        snapshotId: String,
        generation: Long,
        createdAt: Long,
        appVersion: String,
    ): ByteArray {
        val sb = StringBuilder(1024)
        sb.append("{\"formatVersion\":").append(FORMAT_VERSION)
        sb.append(",\"snapshotId\":").append(quote(snapshotId))
        sb.append(",\"generation\":").append(generation)
        sb.append(",\"createdAt\":").append(createdAt)
        sb.append(",\"appVersion\":").append(quote(appVersion))
        sb.append(",\"stores\":{")
        var firstStore = true
        for (file in STORE_FILES) {
            val entries = stores[file] ?: continue
            val encoded = entries.entries.mapNotNull { (k, v) ->
                val sv = SnapValue.of(v) ?: return@mapNotNull null
                k to sv
            }.sortedBy { it.first }
            if (!firstStore) sb.append(',')
            firstStore = false
            sb.append(quote(file)).append(":{")
            var firstKey = true
            for ((k, sv) in encoded) {
                if (!firstKey) sb.append(',')
                firstKey = false
                sb.append(quote(k)).append(":{\"t\":")
                    .append(quote(sv.type.toString()))
                    .append(",\"v\":").append(quote(sv.raw)).append('}')
            }
            sb.append('}')
        }
        sb.append("}}")
        return sb.toString().toByteArray(Charsets.UTF_8)
    }

    /**
     * Parses + validates canonical snapshot bytes. Returns null when the bytes
     * are not a supported snapshot (wrong format version, missing fields,
     * malformed JSON). On success [Decoded.payloadSha256] is the SHA-256 of
     * the exact input bytes.
     */
    fun parse(bytes: ByteArray): Decoded? {
        return try {
            val root = JSONObject(bytes.toString(Charsets.UTF_8))
            if (root.optInt("formatVersion", -1) != FORMAT_VERSION) return null
            val snapshotId = root.optString("snapshotId", "")
            if (snapshotId.isBlank()) return null
            val generation = root.optLong("generation", -1L)
            if (generation < 0) return null
            val createdAt = root.optLong("createdAt", 0L)
            val appVersion = root.optString("appVersion", "")
            val storesJson = root.optJSONObject("stores") ?: return null
            val stores = LinkedHashMap<String, Map<String, SnapValue>>()
            for (file in STORE_FILES) {
                val obj = storesJson.optJSONObject(file) ?: continue
                val map = LinkedHashMap<String, SnapValue>()
                val keys = obj.keys()
                while (keys.hasNext()) {
                    val k = keys.next()
                    val v = obj.optJSONObject(k) ?: continue
                    val t = v.optString("t", "")
                    if (t.length != 1) continue
                    map[k] = SnapValue(t[0], v.optString("v", ""))
                }
                stores[file] = map
            }
            Decoded(
                snapshotId = snapshotId,
                generation = generation,
                createdAt = createdAt,
                appVersion = appVersion,
                stores = stores,
                payloadSha256 = Hashing.sha256Hex(bytes),
            )
        } catch (_: Exception) {
            null
        }
    }

    /** True when every snapshotted store is absent or empty (fresh install). */
    fun isEmptyContent(decoded: Decoded): Boolean =
        decoded.stores.values.all { it.isEmpty() }

    private fun quote(s: String): String {
        val sb = StringBuilder(s.length + 2)
        sb.append('"')
        for (c in s) {
            when (c) {
                '"' -> sb.append("\\\"")
                '\\' -> sb.append("\\\\")
                '\n' -> sb.append("\\n")
                '\r' -> sb.append("\\r")
                '\t' -> sb.append("\\t")
                else -> if (c < ' ') sb.append(String.format("\\u%04x", c.code)) else sb.append(c)
            }
        }
        sb.append('"')
        return sb.toString()
    }

    /** Unquotes a JSON string literal (handles the escapes [quote] emits). */
    internal fun unquote(s: String): String {
        if (s.length < 2 || !s.startsWith('"')) return s
        val sb = StringBuilder(s.length)
        var i = 1
        while (i < s.length - 1) {
            val c = s[i]
            if (c == '\\' && i + 1 < s.length - 1) {
                when (s[i + 1]) {
                    '"' -> sb.append('"')
                    '\\' -> sb.append('\\')
                    'n' -> sb.append('\n')
                    'r' -> sb.append('\r')
                    't' -> sb.append('\t')
                    'u' -> {
                        val hex = s.substring(i + 2, (i + 6).coerceAtMost(s.length - 1))
                        sb.append(hex.toIntOrNull(16)?.toChar() ?: '?')
                        i += 4
                    }
                    else -> sb.append(s[i + 1])
                }
                i += 2
            } else {
                sb.append(c)
                i++
            }
        }
        return sb.toString()
    }
}

/**
 * Minimal comparable identity of one snapshot (local or remote).
 * Cloned from the reference `BackupIdentity` (fields + semantics).
 */
data class SnapshotIdentity(val sha256: String, val generation: Long)

/** Local-vs-remote reconciliation verdict. Cloned from reference `ReconcileDecision`. */
enum class ReconcileDecision {
    NOTHING_TRUSTWORTHY,
    LOCAL_TO_DRIVE,
    DRIVE_TO_LOCAL,
    ALREADY_SYNCED,
    /** Same generation, different content: preserve both, never overwrite/delete. */
    CONFLICT_PRESERVE_BOTH,
    LOCAL_NEWER_TO_DRIVE,
    DRIVE_NEWER_TO_LOCAL,
}

/**
 * Pure reconciliation policy. Priority: existing verified local beats verified
 * Drive; never replace known-good with unknown/unverified.
 * Cloned from the reference `ReconcilePolicy` (rules identical).
 */
object ReconcilePolicy {
    fun decide(local: SnapshotIdentity?, drive: SnapshotIdentity?): ReconcileDecision {
        val l = local ?: return if (drive == null) {
            ReconcileDecision.NOTHING_TRUSTWORTHY
        } else {
            ReconcileDecision.DRIVE_TO_LOCAL
        }
        val d = drive ?: return ReconcileDecision.LOCAL_TO_DRIVE
        if (l.sha256 == d.sha256) return ReconcileDecision.ALREADY_SYNCED
        if (l.generation == d.generation) return ReconcileDecision.CONFLICT_PRESERVE_BOTH
        return if (l.generation > d.generation) ReconcileDecision.LOCAL_NEWER_TO_DRIVE
        else ReconcileDecision.DRIVE_NEWER_TO_LOCAL
    }
}
