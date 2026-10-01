package com.vishal.riy.drive

import android.accounts.Account
import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import com.vishal.riy.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.util.UUID

/**
 * Drive backup orchestrator for riy's protection state — the single owner of
 * snapshot → verify → publish → restore for the six SharedPreferences stores.
 *
 * Architecture cloned from the reference `BackupManager`, adapted to riy's
 * data model (SharedPreferences snapshots instead of Room/SQLite; no images):
 *
 *  - Local stores are the source of truth. Drive is never required for
 *    protection to work; every failure degrades to local-only, never to loss.
 *  - After any store write, [DriveSync.requestBackup] (debounced,
 *    single-flight) → [backupAfterTransaction]: build canonical snapshot →
 *    publish via [RemoteCoordinator] (candidate → verify → promote → confirm).
 *  - Same content → ALREADY_VERIFIED, zero writes, no generation bump.
 *  - On startup, [autoRestoreIfNeeded] restores a verified VAULT only when
 *    local state is completely empty (fresh install/reinstall) — valid local
 *    data is never overwritten automatically.
 *  - A persistent pending-sync queue (snapshot identity only, never raw data)
 *    survives process death; [flushPendingSync] replays it on foreground and
 *    on connectivity return.
 *  - Account + folder id + provisioning are persisted per-account; an id from
 *    account A is never reused for account B.
 */
class DriveBackupManager(private val appContext: Context) {

    private val prefs: SharedPreferences = appContext.applicationContext
        .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private val opMutex = Mutex()

    @Volatile private var coordinator: RemoteCoordinator? = null

    // ------------------------------------------------------------ snapshots

    /** Canonical bytes of the CURRENT local state (generation = last promoted). */
    private fun currentSnapshotBytes(): Pair<ByteArray, Long> {
        val gen = prefs.getLong(KEY_GENERATION, 0L)
        val stores = LinkedHashMap<String, Map<String, *>>()
        for (file in RiySnapshot.STORE_FILES) {
            val sp = appContext.applicationContext.getSharedPreferences(file, Context.MODE_PRIVATE)
            stores[file] = sp.all
        }
        val bytes = RiySnapshot.build(
            stores = stores,
            snapshotId = prefs.getString(KEY_SNAPSHOT_ID, null) ?: UUID.randomUUID().toString().also {
                prefs.edit().putString(KEY_SNAPSHOT_ID, it).apply()
            },
            generation = gen,
            createdAt = System.currentTimeMillis(),
            appVersion = BuildConfig.VERSION_NAME,
        )
        return bytes to gen
    }

    private fun localIdentity(): SnapshotIdentity? {
        return try {
            val (bytes, gen) = currentSnapshotBytes()
            SnapshotIdentity(sha256 = Hashing.sha256Hex(bytes), generation = gen)
        } catch (_: Exception) {
            null
        }
    }

    /** True when every snapshotted store is at defaults (fresh install). */
    private fun localEmpty(): Boolean {
        for (file in RiySnapshot.STORE_FILES) {
            val sp = appContext.applicationContext.getSharedPreferences(file, Context.MODE_PRIVATE)
            if (sp.all.isNotEmpty()) return false
        }
        return true
    }

    // ------------------------------------------------------------ publication

    /**
     * Called (debounced) after any protection store changes. Builds the
     * canonical snapshot and publishes it. Never throws.
     */
    suspend fun backupAfterTransaction() {
        try {
            opMutex.withLock { backupLocked() }
        } catch (e: Exception) {
            Log.w(TAG, "backupAfterTransaction failed: ${e.javaClass.simpleName}: ${e.message}")
        }
    }

    private suspend fun backupLocked() {
        val rc = coordinator ?: run {
            Log.i(TAG, "backup skipped: Drive not connected")
            return
        }
        val (bytes, gen) = currentSnapshotBytes()
        val sha = Hashing.sha256Hex(bytes)
        val lastSha = prefs.getString(KEY_LAST_SHA, null)
        // New content → new generation (monotonic; drives anti-rollback).
        val useGen: Long
        val useBytes: ByteArray
        if (lastSha != sha) {
            useGen = gen + 1
            useBytes = RiySnapshot.build(
                stores = dumpStores(),
                snapshotId = prefs.getString(KEY_SNAPSHOT_ID, null) ?: UUID.randomUUID().toString(),
                generation = useGen,
                createdAt = System.currentTimeMillis(),
                appVersion = BuildConfig.VERSION_NAME,
            )
        } else {
            useGen = gen
            useBytes = bytes
        }
        val useSha = Hashing.sha256Hex(useBytes)
        val outcome = try {
            rc.sync(SnapshotIdentity(useSha, useGen), useBytes, System.currentTimeMillis())
        } catch (e: Exception) {
            Log.w(TAG, "sync threw: ${e.javaClass.simpleName}: ${e.message}")
            markSyncPending(useSha, useGen)
            return
        }
        when (outcome) {
            UploadOutcome.VERIFIED_AND_PROMOTED -> {
                prefs.edit()
                    .putLong(KEY_GENERATION, useGen)
                    .putString(KEY_LAST_SHA, useSha)
                    .putLong(KEY_LAST_BACKUP, System.currentTimeMillis())
                    .apply()
                clearSyncPendingIfMatches(useSha)
                Log.i(TAG, "backup promoted gen=$useGen sha=${useSha.take(8)}")
            }
            UploadOutcome.ALREADY_VERIFIED -> {
                prefs.edit().putLong(KEY_LAST_BACKUP, System.currentTimeMillis()).apply()
                clearSyncPendingIfMatches(useSha)
                Log.i(TAG, "backup already verified gen=$useGen (zero writes)")
            }
            UploadOutcome.VERIFIED_NO_PROMOTION -> {
                // Remote holds newer/same-gen-different content: preserve both,
                // never overwrite local; keep the pending entry for a later retry.
                markSyncPending(useSha, useGen)
                Log.w(TAG, "backup not promoted (remote newer/conflict) — local preserved")
            }
            UploadOutcome.FAILED -> {
                markSyncPending(useSha, useGen)
                Log.w(TAG, "backup FAILED — queued for retry")
            }
        }
    }

    private fun dumpStores(): Map<String, Map<String, *>> {
        val stores = LinkedHashMap<String, Map<String, *>>()
        for (file in RiySnapshot.STORE_FILES) {
            stores[file] = appContext.applicationContext.getSharedPreferences(file, Context.MODE_PRIVATE).all
        }
        return stores
    }

    // ------------------------------------------------------------ pending queue

    private fun markSyncPending(sha: String, gen: Long) {
        prefs.edit()
            .putString(KEY_SYNC_SHA, sha)
            .putLong(KEY_SYNC_GEN, gen)
            .putLong(KEY_SYNC_AT, System.currentTimeMillis())
            .apply()
    }

    private fun clearSyncPendingIfMatches(sha: String) {
        if (prefs.getString(KEY_SYNC_SHA, null) == sha) {
            prefs.edit()
                .remove(KEY_SYNC_SHA).remove(KEY_SYNC_GEN)
                .remove(KEY_SYNC_AT).remove(KEY_SYNC_ATTEMPTS)
                .apply()
        }
    }

    /** Replays the persisted pending-sync queue. Safe to call on foreground/reconnect. */
    suspend fun flushPendingSync(): Boolean = withContext(Dispatchers.IO) {
        val pendingSha = prefs.getString(KEY_SYNC_SHA, null) ?: return@withContext true
        val rc = coordinator ?: return@withContext false
        return@withContext try {
            opMutex.withLock {
                val (bytes, _) = currentSnapshotBytes()
                val sha = Hashing.sha256Hex(bytes)
                // Only replay when the pending identity still matches current state;
                // otherwise a fresh backupAfterTransaction owns the newer content.
                if (sha != pendingSha) {
                    backupLocked()
                    true
                } else {
                    val gen = prefs.getLong(KEY_SYNC_GEN, prefs.getLong(KEY_GENERATION, 0L))
                    val useBytes: ByteArray
                    val useGen: Long
                    if (sha != prefs.getString(KEY_LAST_SHA, null)) {
                        useGen = prefs.getLong(KEY_GENERATION, 0L) + 1
                        useBytes = RiySnapshot.build(
                            dumpStores(),
                            prefs.getString(KEY_SNAPSHOT_ID, null) ?: UUID.randomUUID().toString(),
                            useGen, System.currentTimeMillis(), BuildConfig.VERSION_NAME,
                        )
                    } else {
                        useGen = gen
                        useBytes = bytes
                    }
                    val useSha = Hashing.sha256Hex(useBytes)
                    when (rc.sync(SnapshotIdentity(useSha, useGen), useBytes, System.currentTimeMillis())) {
                        UploadOutcome.VERIFIED_AND_PROMOTED -> {
                            prefs.edit().putLong(KEY_GENERATION, useGen)
                                .putString(KEY_LAST_SHA, useSha)
                                .putLong(KEY_LAST_BACKUP, System.currentTimeMillis()).apply()
                            clearSyncPendingIfMatches(useSha)
                            true
                        }
                        UploadOutcome.ALREADY_VERIFIED -> {
                            clearSyncPendingIfMatches(useSha)
                            true
                        }
                        else -> false
                    }
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "flushPendingSync failed: ${e.javaClass.simpleName}")
            false
        }
    }

    // ------------------------------------------------------------ startup restore

    /**
     * Restores a verified VAULT only when local state is completely empty AND
     * this install has not restored before. Valid local data is never
     * overwritten. Returns true when a restore was applied.
     */
    suspend fun autoRestoreIfNeeded(): Boolean = withContext(Dispatchers.IO) {
        if (prefs.getBoolean(KEY_AUTO_RESTORED, false)) return@withContext false
        if (!localEmpty()) return@withContext false
        val rc = coordinator ?: return@withContext false
        return@withContext try {
            val bytes = rc.fetchBytes() ?: return@withContext false
            val decoded = RiySnapshot.parse(bytes) ?: return@withContext false
            if (Hashing.sha256Hex(bytes) != decoded.payloadSha256) return@withContext false
            applySnapshot(decoded)
            prefs.edit()
                .putBoolean(KEY_AUTO_RESTORED, true)
                .putLong(KEY_GENERATION, decoded.generation)
                .putString(KEY_LAST_SHA, decoded.payloadSha256)
                .apply()
            Log.i(TAG, "auto-restore applied gen=${decoded.generation}")
            true
        } catch (e: Exception) {
            Log.w(TAG, "auto-restore failed: ${e.javaClass.simpleName}")
            false
        }
    }

    /** Writes a verified snapshot into the six local stores (typed round-trip). */
    private fun applySnapshot(decoded: RiySnapshot.Decoded) {
        val app = appContext.applicationContext
        for (file in RiySnapshot.STORE_FILES) {
            val entries = decoded.stores[file] ?: continue
            val sp = app.getSharedPreferences(file, Context.MODE_PRIVATE)
            val ed = sp.edit()
            ed.clear()
            for ((k, sv) in entries) {
                when (sv.type) {
                    's' -> ed.putString(k, sv.raw)
                    'i' -> sv.raw.toIntOrNull()?.let { ed.putInt(k, it) }
                    'l' -> sv.raw.toLongOrNull()?.let { ed.putLong(k, it) }
                    'f' -> sv.raw.toFloatOrNull()?.let { ed.putFloat(k, it) }
                    'b' -> ed.putBoolean(k, sv.raw.toBoolean())
                    'e' -> ed.putStringSet(k, sv.toValue() as Set<String>)
                }
            }
            ed.apply()
        }
    }

    // ------------------------------------------------------------ auth / provisioning

    /** True when Drive is enabled but no account is authorized yet. */
    suspend fun driveNeedsAuthorization(): Boolean = withContext(Dispatchers.IO) {
        RiyDriveConfig.enabled && prefs.getString(KEY_ACCOUNT, null).isNullOrBlank()
    }

    /**
     * Silent session validation for startup. A persisted session counts as valid
     * ONLY when a real OAuth access token is obtainable RIGHT NOW for the stored
     * account — that token is the proof of authentication. The stored email is
     * only a pointer to which account to ask about; it is never accepted on its
     * own.
     *
     * The GoogleSignIn cache is deliberately NOT consulted. It lives in a separate
     * store from the OAuth grant and is routinely empty after a process kill, an
     * app update, or Play Services cache eviction. Gating on it meant a returning
     * user with a perfectly valid Drive grant was pushed back to the account picker
     * on every launch.
     *
     * `GoogleAuthUtil.getToken` transparently refreshes an expired access token,
     * so an ordinary expiry recovers here without any UI (requirement 7). The
     * coordinator is re-attached when missing. Never throws.
     */
    suspend fun validateStoredSession(): Boolean = withContext(Dispatchers.IO) {
        return@withContext try {
            val email = prefs.getString(KEY_ACCOUNT, null)?.trim()?.takeIf { it.isNotEmpty() }
                ?: return@withContext false
            // Proof of a live grant: a usable token must be obtainable NOW.
            if (GoogleDriveAuth.usableToken(appContext, email) == null) return@withContext false
            if (coordinator == null) attachCoordinator(email)
            Log.i(TAG, "session restored silently for $email")
            true
        } catch (e: Exception) {
            Log.w(TAG, "silent session validation failed: ${e.javaClass.simpleName}")
            false
        }
    }

    /** The remembered Drive account, or null when nothing is authorized. */
    suspend fun storedAccount(): String? = withContext(Dispatchers.IO) {
        prefs.getString(KEY_ACCOUNT, null)?.trim()?.takeIf { it.isNotEmpty() }
    }

    /**
     * True once the user has explicitly logged out. Persisted (not just an
     * in-memory flag) so it survives process death, force-stop and app update,
     * and guarantees the next launch asks for the account again.
     */
    suspend fun isLoggedOut(): Boolean = withContext(Dispatchers.IO) {
        prefs.getBoolean(KEY_LOGGED_OUT, false)
    }

    /**
     * Explicit logout: signs the platform session out and drops every
     * account-scoped key (account, folder id, provisioning, pending queue).
     * Local protection data is NEVER touched — only the Drive link is cut.
     * The next launch will genuinely require the account picker again.
     */
    suspend fun signOut(): Boolean = withContext(Dispatchers.IO) {
        return@withContext try {
            runCatching {
                com.google.android.gms.tasks.Tasks.await(
                    com.google.android.gms.auth.api.signin.GoogleSignIn
                        .getClient(appContext, GoogleDriveAuth.signInOptions())
                        .signOut(),
                )
            }
            prefs.edit()
                .remove(KEY_ACCOUNT).remove(KEY_NAME).remove(KEY_PHOTO)
                .remove(KEY_FOLDER).remove(KEY_FOLDER_ACCOUNT).remove(KEY_PROVISIONED)
                .remove(KEY_SYNC_SHA).remove(KEY_SYNC_GEN)
                .remove(KEY_SYNC_AT).remove(KEY_SYNC_ATTEMPTS)
                // Persisted so the next launch is REQUIRED to show the picker,
                // even if the platform still holds the grant.
                .putBoolean(KEY_LOGGED_OUT, true)
                .apply()
            coordinator = null
            true
        } catch (_: Exception) {
            false
        }
    }

    /** Remembers the authorized account and wires the coordinator. Never stores tokens. */
    suspend fun connectDrive(email: String, displayName: String? = null, photoUrl: String? = null) =
        withContext(Dispatchers.IO) {
            val clean = email.trim()
            val old = prefs.getString(KEY_ACCOUNT, null)
            if (old != clean) {
                // Account changed → account-scoped folder id + provisioning invalidated.
                prefs.edit()
                    .putString(KEY_ACCOUNT, clean)
                    .remove(KEY_FOLDER).remove(KEY_FOLDER_ACCOUNT).remove(KEY_PROVISIONED)
                    .apply()
            }
            if (!displayName.isNullOrBlank()) prefs.edit().putString(KEY_NAME, displayName).apply()
            if (!photoUrl.isNullOrBlank()) prefs.edit().putString(KEY_PHOTO, photoUrl).apply()
            // A completed interactive sign-in ends any logged-out state.
            prefs.edit().putBoolean(KEY_LOGGED_OUT, false).apply()
            attachCoordinator(clean)
            Log.i(TAG, "Drive connected: $clean")
        }

    private fun attachCoordinator(email: String) {
        val folderId = prefs.getString(KEY_FOLDER, null)
            ?.takeIf { prefs.getString(KEY_FOLDER_ACCOUNT, null) == email }
        coordinator = RemoteCoordinator(
            store = GoogleDriveBackupStore(appContext, Account(email, "com.google"), persistedFolderId = folderId),
            destination = "drive:$email",
        )
    }

    /** Resolves/verifies the RiyBackup folder and marks provisioning (folder existence, not pointer). */
    suspend fun finalizeProvisioning(): Boolean = withContext(Dispatchers.IO) {
        val email = prefs.getString(KEY_ACCOUNT, null) ?: return@withContext false
        val rc = coordinator ?: run { attachCoordinator(email); coordinator } ?: return@withContext false
        return@withContext try {
            val fid = rc.folderId() ?: return@withContext false
            prefs.edit()
                .putString(KEY_FOLDER, fid)
                .putString(KEY_FOLDER_ACCOUNT, email)
                .putBoolean(KEY_PROVISIONED, true)
                .apply()
            true
        } catch (e: Exception) {
            Log.w(TAG, "finalizeProvisioning failed: ${e.javaClass.simpleName}")
            false
        }
    }

    /** Re-attaches the coordinator on startup when an account is remembered. */
    suspend fun reconcileOnStartup(): Boolean = withContext(Dispatchers.IO) {
        val email = prefs.getString(KEY_ACCOUNT, null) ?: return@withContext false
        return@withContext try {
            attachCoordinator(email)
            finalizeProvisioning()
        } catch (_: Exception) {
            false
        }
    }

    /** True when the remote destination is reachable (account + folder resolve). */
    suspend fun remoteReachable(): Boolean = withContext(Dispatchers.IO) {
        return@withContext try {
            coordinator?.folderId() != null
        } catch (_: Exception) {
            false
        }
    }

    /** Reconciles local vs remote identity; uploads when local is newer. Never overwrites local. */
    suspend fun reconcileAndCatchUp(): ReconcileDecision = withContext(Dispatchers.IO) {
        val rc = coordinator ?: return@withContext ReconcileDecision.NOTHING_TRUSTWORTHY
        return@withContext try {
            val local = localIdentity()
            val drive = rc.currentVaultPoint()
            when (val d = ReconcilePolicy.decide(local, drive)) {
                ReconcileDecision.LOCAL_TO_DRIVE, ReconcileDecision.LOCAL_NEWER_TO_DRIVE -> {
                    opMutex.withLock { backupLocked() }
                    d
                }
                else -> d // DRIVE sides only matter for empty-local restore; conflicts preserve local.
            }
        } catch (e: Exception) {
            Log.w(TAG, "reconcile failed: ${e.javaClass.simpleName}")
            ReconcileDecision.NOTHING_TRUSTWORTHY
        }
    }

    // ------------------------------------------------------------ status

    data class DriveStatus(
        val enabled: Boolean,
        val account: String?,
        val provisioned: Boolean,
        val lastBackupAt: Long,
        val pendingSync: Boolean,
    )

    suspend fun status(): DriveStatus = withContext(Dispatchers.IO) {
        val email = prefs.getString(KEY_ACCOUNT, null)
        DriveStatus(
            enabled = RiyDriveConfig.enabled,
            account = email,
            provisioned = email != null && prefs.getBoolean(KEY_PROVISIONED, false) &&
                prefs.getString(KEY_FOLDER_ACCOUNT, null) == email &&
                !prefs.getString(KEY_FOLDER, null).isNullOrBlank(),
            lastBackupAt = prefs.getLong(KEY_LAST_BACKUP, 0L),
            pendingSync = prefs.getString(KEY_SYNC_SHA, null) != null,
        )
    }

    private companion object {
        const val TAG = "RiyDrive"
        const val PREFS_NAME = "riy_drive_prefs"
        const val KEY_ACCOUNT = "drive_account"
        const val KEY_NAME = "drive_name"
        const val KEY_PHOTO = "drive_photo"
        const val KEY_FOLDER = "drive_folder"
        const val KEY_FOLDER_ACCOUNT = "drive_folder_account"
        const val KEY_PROVISIONED = "drive_provisioned"
        const val KEY_AUTO_RESTORED = "auto_restored"
        const val KEY_GENERATION = "snapshot_generation"
        const val KEY_SNAPSHOT_ID = "snapshot_id"
        const val KEY_LAST_SHA = "last_snapshot_sha"
        const val KEY_LAST_BACKUP = "last_backup"
        const val KEY_SYNC_SHA = "sync_pending_sha"
        const val KEY_SYNC_GEN = "sync_pending_gen"
        const val KEY_SYNC_AT = "sync_pending_at"
        const val KEY_SYNC_ATTEMPTS = "sync_pending_attempts"
        /** Set by an explicit logout; cleared by a completed sign-in. */
        const val KEY_LOGGED_OUT = "drive_logged_out"
    }
}
