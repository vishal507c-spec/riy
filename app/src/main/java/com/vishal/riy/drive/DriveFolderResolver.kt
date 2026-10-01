package com.vishal.riy.drive

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.IOException

/**
 * A minimal Drive gateway used to find/validate/create the single app folder
 * ("RiyBackup"). Real implementation in [GoogleDriveBackupStore] uses the Drive
 * REST API (with full pagination + exact matching); tests use a fake. Kept pure
 * so the search → reuse / missing → create-then-verify policy is testable.
 *
 * Cloned from the reference `DriveFolderGateway` (contract identical).
 */
interface DriveFolderGateway {
    /**
     * ALL folder ids whose name EXACTLY equals [name], are folders, and are not
     * trashed — across every result page, in a deterministic order (oldest
     * created first, id as tie-break). Files named like the folder never match.
     */
    suspend fun findFolders(name: String): List<String>

    /** Create the folder; returns its id. Throws on failure OR unknown result (timeout). */
    suspend fun createFolder(name: String): String

    /**
     * True only if [id] currently exists, is a folder, and is not trashed.
     * False means definitively invalid. Throws on transient/unknown errors
     * (an unknown result must never be treated as proof of absence).
     */
    suspend fun isValidFolder(id: String): Boolean
}

/**
 * Resolves ONE canonical logical backup folder ("RiyBackup") on a Drive
 * destination, per Google account. The user NEVER selects a folder.
 *
 * Deterministic policy (idempotent under retry, crash, concurrency, reinstall):
 *  1. A persisted/cached folder id is VALIDATED once before being trusted
 *     (cache is only an optimization — correctness wins).
 *  2. SEARCH before CREATE, always. A missing cache never triggers a blind create.
 *  3. If multiple valid folders already exist, reuse the deterministic first one;
 *     never create another and never delete anything.
 *  4. Create only when ZERO valid folders exist; a create timeout is NOT proof of
 *     failure → re-search before any retry.
 *  5. Create-then-verify: the new id is validated before it is cached/persisted.
 *     If the app dies before persistence, the next run rediscovers the folder.
 *  6. Single-flight: concurrent callers (startup, workers, retry, reconnect)
 *     serialize on a [Mutex] so no two can provision independently.
 *
 * Each Google account gets its OWN resolver instance seeded with its OWN
 * persisted folder id — ids are never shared across accounts.
 *
 * Cloned from the reference `DriveFolderResolver` (policy identical).
 */
class DriveFolderResolver(
    private val folderName: String,
    private val gateway: DriveFolderGateway,
    persistedId: String? = null,
) {
    private val mutex = Mutex()

    @Volatile private var cachedId: String? = persistedId?.trim()?.takeIf { it.isNotEmpty() }

    @Volatile private var validated: Boolean = false

    suspend fun locate(): String = mutex.withLock { locateLocked() }

    private suspend fun locateLocked(): String {
        // 1. Trust-but-verify the cached/persisted id (once per process).
        cachedId?.let { id ->
            if (validated) return id
            val ok = try {
                gateway.isValidFolder(id)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                throw e // unknown ≠ invalid: keep the cache, surface the failure
            }
            if (ok) {
                validated = true
                return id
            }
            // Definitively invalid (deleted/trashed/wrong account) → auto-heal by re-searching.
            cachedId = null
        }
        // 2. Search before create.
        discover()?.let { return it }
        // 3. Create exactly one — with timeout safety.
        val created = try {
            gateway.createFolder(folderName)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // A timeout/unknown result is NOT proof that creation failed.
            discover()?.let { return it }
            throw e
        }
        // 4. Create-then-verify before trusting the new id.
        val valid = try {
            gateway.isValidFolder(created)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            discover()?.let { return it }
            throw e
        }
        if (!valid) {
            discover()?.let { return it }
            throw IOException("created $folderName folder failed verification")
        }
        cachedId = created
        validated = true
        return created
    }

    /** Search all pages; reuse the deterministic first valid folder if any exists. */
    private suspend fun discover(): String? {
        val found = try {
            gateway.findFolders(folderName)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            emptyList()
        }
        val id = found.firstOrNull() ?: return null
        cachedId = id
        validated = true
        return id
    }

    /** Drop the cached id (e.g. after a definitive invalidation elsewhere); next locate re-searches. */
    suspend fun reset(): Unit = mutex.withLock {
        cachedId = null
        validated = false
    }

    fun cached(): String? = cachedId
}
