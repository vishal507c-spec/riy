package com.vishal.riy.drive

import org.json.JSONObject

/**
 * A non-live, blob-like listing of objects as observed on a destination.
 * `contentSha` is informational only — selection/verification always uses
 * [Hashing] SHA-256 computed locally, never this field.
 */
data class RemoteObject(val name: String, val sizeBytes: Long, val contentSha: String?)

/**
 * Metadata persisted per destination tracking the monotonic LATEST_VERIFIED
 * pointer. The pointer is an OPTIONAL accelerator only: it can never override
 * the VAULT and is rebuilt from the VAULT when missing/corrupt/stale.
 */
data class LatestPointer(val name: String, val generation: Long, val createdAt: Long = 0L) {
    fun toBytes(): ByteArray = JSONObject()
        .put("name", name)
        .put("generation", generation)
        .put("createdAt", createdAt)
        .toString().toByteArray(Charsets.UTF_8)

    companion object {
        const val META = "_latest_verified.json"
        fun from(bytes: ByteArray?): LatestPointer? = bytes?.let {
            runCatching {
                val o = JSONObject(it.toString(Charsets.UTF_8))
                LatestPointer(
                    name = o.getString("name"),
                    generation = o.getLong("generation"),
                    createdAt = o.optLong("createdAt", 0L),
                )
            }.getOrNull()
        }
    }
}

/**
 * A generic, offline-neutral blob store for ONE destination (the device's
 * Drive folder). Everything is identified by immutable content-addressed
 * names, so uploads are idempotent and verifiable by re-reading.
 * Implementations: Drive REST, or the in-memory fake used by tests.
 * Never the source of truth on its own.
 *
 * Cloned from the reference `RemoteBackupStore` contract (method set +
 * semantics identical).
 */
interface RemoteBackupStore {
    suspend fun list(): List<RemoteObject>
    suspend fun exists(name: String): Boolean
    suspend fun put(name: String, bytes: ByteArray): Boolean
    suspend fun get(name: String): ByteArray?
    suspend fun delete(name: String): Boolean

    /**
     * Content-addressed duplicate search: objects that contain [sha] in their
     * name. Default is a filtered listing; the Drive implementation uses a
     * targeted, paginated server-side query.
     */
    suspend fun findBySha(sha: String): List<RemoteObject> =
        list().filter { it.name.endsWith(".sqlite") && it.name.contains(sha) }

    /** The app-managed destination folder id (Drive: RiyBackup); null if n/a. */
    suspend fun folderId(): String? = null
}

/** Outcome of one coordinator publication attempt. */
enum class UploadOutcome { VERIFIED_AND_PROMOTED, VERIFIED_NO_PROMOTION, ALREADY_VERIFIED, FAILED }

/**
 * In-memory [RemoteBackupStore] fake for JVM unit tests — proves the
 * coordinator's two-phase commit, verification, monotonic pointer,
 * idempotency and reconciliation without network or credentials.
 *
 * Cloned from the reference `InMemoryRemoteStore` (behavior identical).
 */
class InMemoryRemoteStore : RemoteBackupStore {
    private val objects = LinkedHashMap<String, ByteArray>()

    /** When true, [put] fails without writing. */
    var failPut: Boolean = false

    /** When true, [get] returns null. */
    var failGet: Boolean = false

    /** When set, [get] returns only the first half of this object's bytes. */
    var corruptOnGet: String? = null

    override suspend fun list(): List<RemoteObject> =
        objects.map { (n, b) -> RemoteObject(n, b.size.toLong(), Hashing.sha256Hex(b)) }

    override suspend fun exists(name: String): Boolean = objects.containsKey(name)

    override suspend fun put(name: String, bytes: ByteArray): Boolean {
        if (failPut) return false
        objects[name] = bytes
        return true
    }

    override suspend fun get(name: String): ByteArray? {
        if (failGet) return null
        val b = objects[name] ?: return null
        return if (corruptOnGet == name) b.copyOfRange(0, (b.size / 2).coerceAtLeast(1)) else b
    }

    override suspend fun delete(name: String): Boolean {
        objects.remove(name)
        return true
    }

    fun seed(name: String, bytes: ByteArray) {
        objects[name] = bytes
    }

    fun raw(name: String): ByteArray? = objects[name]

    fun names(): List<String> = objects.keys.toList()
}
