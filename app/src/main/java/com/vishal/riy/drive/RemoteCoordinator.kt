package com.vishal.riy.drive

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Result of inspecting a remote object's OWN self-describing snapshot header.
 * Validity/selection is driven by this — NEVER by the filename.
 */
data class VaultProbe(
    val payloadSha: String,
    val snapshotVersion: Long,
    val datasetIdentity: String,
    val valid: Boolean,
)

/** Reads a vault's embedded header + integrity and reports its authoritative identity. */
typealias VaultProbeFn = (ByteArray) -> VaultProbe

/**
 * THE MASTER VAULT remote coordinator — one destination, one authoritative object.
 *
 * The canonical remote recovery object is the self-describing VAULT under a STABLE
 * name ("VAULT"). Validity and selection come from the VAULT's embedded header +
 * integrity (via [VaultProbeFn]), never from the filename. `_latest_verified.json`
 * is demoted to an OPTIONAL accelerator/cache that is rebuilt from the VAULT when
 * missing/corrupt/stale and can NEVER override the VAULT.
 *
 * Safe publication: candidate → upload → verify → promote → confirm.
 * The trusted VAULT is never destroyed before a new candidate is fully verified; a
 * failed run leaves the old VAULT authoritative and cleans its own transient
 * candidate (retry re-uploads from the local snapshot — nothing is lost).
 * Anti-rollback uses the embedded generation (header authoritative, pointer as
 * fallback). Single-flight + content dedup preserved.
 *
 * Cloned from the reference `RemoteCoordinator` (publication state machine
 * identical; only the probe is snapshot-JSON instead of SQLite-header based).
 */
class RemoteCoordinator(
    private val store: RemoteBackupStore,
    val destination: String,
    private val probe: VaultProbeFn = ::defaultProbe,
) {

    /**
     * Single-flight for remote sync/promotion. Concurrent callers converge on
     * one publication; identity is re-checked under the lock before any upload.
     * Lock ordering: acquire remote mutex ONLY (never hold a local mutex while
     * acquiring this one) — the manager snapshots locally first, then calls sync().
     */
    private val syncMutex = Mutex()

    suspend fun latest(): LatestPointer? = LatestPointer.from(store.get(LatestPointer.META))

    /** The underlying destination (used by diagnostics). */
    fun backupStore(): RemoteBackupStore = store

    // ---------------------------------------------------------------- publication

    /**
     * Publish the canonical VAULT. The authoritative remote object is the stable
     * [VAULT_NAME]; identity comes from the snapshot header via [probe].
     */
    suspend fun sync(point: SnapshotIdentity, bytes: ByteArray, createdAt: Long): UploadOutcome =
        syncMutex.withLock {
            val sha = Hashing.sha256Hex(bytes)
            if (sha != point.sha256) return@withLock UploadOutcome.FAILED // never upload bytes that don't match the claimed identity

            // Already the authoritative VAULT (same content) → idempotent, zero writes.
            val current = store.get(VAULT_NAME)?.let { probe(it) }
            if (current != null && current.valid && current.payloadSha == sha) {
                writePointerAccelerator(
                    if (current.snapshotVersion >= 0) current.snapshotVersion else point.generation,
                    point.createdAt(createdAt),
                )
                return@withLock UploadOutcome.ALREADY_VERIFIED
            }

            // 1. Upload the CANDIDATE (never touch the trusted VAULT yet).
            //    TRANSIENT staging only: it must never survive a settled run.
            if (!store.put(VAULT_CANDIDATE_NAME, bytes)) return@withLock UploadOutcome.FAILED
            // 2. Re-read + fully verify the candidate by its OWN header + integrity.
            val candidate = store.get(VAULT_CANDIDATE_NAME) ?: run {
                store.delete(VAULT_CANDIDATE_NAME)
                return@withLock UploadOutcome.FAILED
            }
            val probeC = probe(candidate)
            if (!probeC.valid || probeC.payloadSha != sha) {
                store.delete(VAULT_CANDIDATE_NAME) // staging artifact only — trusted VAULT untouched
                return@withLock UploadOutcome.FAILED
            }
            // 3. ANTI-ROLLBACK: never demote a newer verified VAULT. The embedded
            //    generation is authoritative; the pointer generation is fallback.
            //    A verified-but-not-promoted candidate is cleaned here so the settled
            //    Drive holds only VAULT. Retry re-uploads from the local snapshot.
            val curGen = if (current != null && current.snapshotVersion >= 0) current.snapshotVersion
            else (latest()?.generation ?: -1L)
            val candGen = if (probeC.snapshotVersion >= 0) probeC.snapshotVersion else point.generation
            if (candGen < curGen) {
                store.delete(VAULT_CANDIDATE_NAME) // transient staging only — old VAULT stays authoritative
                return@withLock UploadOutcome.VERIFIED_NO_PROMOTION // candidate valid but older
            }
            if (candGen == curGen && current != null && current.payloadSha != sha) {
                // Same generation, DIFFERENT content must NEVER flip the authoritative VAULT.
                store.delete(VAULT_CANDIDATE_NAME) // transient staging only — never a second tree
                return@withLock UploadOutcome.VERIFIED_NO_PROMOTION
            }
            // 4. Promote candidate → VAULT (old VAULT remains until this write completes).
            if (!store.put(VAULT_NAME, candidate)) {
                store.delete(VAULT_CANDIDATE_NAME)
                return@withLock UploadOutcome.FAILED
            }
            // 5. Confirm by re-reading the authoritative VAULT.
            val promoted = store.get(VAULT_NAME)
            if (promoted == null) {
                store.delete(VAULT_CANDIDATE_NAME)
                return@withLock UploadOutcome.FAILED
            }
            val probeP = probe(promoted)
            if (!probeP.valid || probeP.payloadSha != sha) {
                store.delete(VAULT_CANDIDATE_NAME)
                return@withLock UploadOutcome.FAILED
            }
            store.delete(VAULT_CANDIDATE_NAME) // retire the transient candidate (not the VAULT)
            // 6. OPTIONAL accelerator — pointer write failure NEVER fails publication.
            val promotedNow = curGen < 0 || candGen > curGen
            writePointerAccelerator(candGen, point.createdAt(createdAt))
            return@withLock if (promotedNow) UploadOutcome.VERIFIED_AND_PROMOTED else UploadOutcome.VERIFIED_NO_PROMOTION
        }

    private fun SnapshotIdentity.createdAt(fallback: Long): Long = fallback

    private suspend fun writePointerAccelerator(generation: Long, createdAt: Long) {
        // Best-effort, never monotonic-violating.
        val cur = runCatching { latest() }.getOrNull()
        if (cur != null && generation < cur.generation) return
        runCatching { store.put(LatestPointer.META, LatestPointer(VAULT_NAME, generation, createdAt).toBytes()) }
    }

    /** Staging sweep: delete TRANSIENT `*.candidate` objects only. Never touches VAULT/pointer. */
    suspend fun sweepStaging(): List<String> = syncMutex.withLock {
        val doomed = store.list().map { it.name }
            .filter { it.endsWith(".candidate") && it != VAULT_NAME }
        val removed = mutableListOf<String>()
        for (n in doomed) {
            if (runCatching { store.delete(n) }.getOrDefault(false)) removed += n
        }
        return@withLock removed.sorted()
    }

    /** Re-verify that [point]'s bytes are still what the destination holds. */
    suspend fun reverify(point: SnapshotIdentity): Boolean {
        val bytes = store.get(VAULT_NAME) ?: return false
        val p = probe(bytes)
        return p.valid && p.payloadSha == point.sha256
    }

    /** Download the authoritative VAULT bytes, or null if unavailable. */
    suspend fun fetchBytes(): ByteArray? = store.get(VAULT_NAME)

    suspend fun objectNames(): List<String> = store.list().map { it.name }

    suspend fun folderId(): String? = store.folderId()

    /** The current authoritative VAULT identity, or null when absent/invalid. */
    suspend fun currentVaultPoint(): SnapshotIdentity? {
        val bytes = store.get(VAULT_NAME) ?: return null
        val p = probe(bytes)
        if (!p.valid) return null
        return SnapshotIdentity(sha256 = p.payloadSha, generation = p.snapshotVersion)
    }

    /** Rebuilds the pointer accelerator from the VAULT. Never deletes. */
    suspend fun rebuildPointerFromVault(createdAt: Long): Boolean {
        val bytes = store.get(VAULT_NAME) ?: return false
        val p = probe(bytes)
        if (!p.valid) return false
        writePointerAccelerator(p.snapshotVersion, createdAt)
        return true
    }

    suspend fun repairStalePointer(createdAt: Long): Boolean = syncMutex.withLock {
        rebuildPointerFromVault(createdAt)
    }

    companion object {
        const val VAULT_NAME = "VAULT"
        const val VAULT_CANDIDATE_NAME = "VAULT.candidate"

        /**
         * Default probe: SHA-256 of the bytes + generation parsed from the
         * snapshot JSON header. Valid only when the bytes parse as a supported
         * snapshot.
         */
        fun defaultProbe(bytes: ByteArray): VaultProbe {
            val sha = Hashing.sha256Hex(bytes)
            val decoded = RiySnapshot.parse(bytes)
            return if (decoded == null) {
                VaultProbe(payloadSha = sha, snapshotVersion = -1L, datasetIdentity = sha, valid = false)
            } else {
                VaultProbe(
                    payloadSha = sha,
                    snapshotVersion = decoded.generation,
                    datasetIdentity = sha,
                    valid = true,
                )
            }
        }
    }
}
