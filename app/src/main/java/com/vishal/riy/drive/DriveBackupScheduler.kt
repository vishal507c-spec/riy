package com.vishal.riy.drive

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Automatic backup scheduler: debounced, single-flight, never-blocking.
 *
 * Contract (cloned from the reference `AutoBackupScheduler`):
 *  - [requestBackup] is called ONLY after a local protection store changes.
 *  - Never blocks the writer; backup failures never break protection logic.
 *  - Multiple rapid changes are coalesced (debounced) into one snapshot.
 *  - Only one backup job runs at a time (single-flight via [Mutex]).
 *  - Reuses the existing backup engine via [backupFn] — no second engine.
 *
 * Correctness > speed: debounce only coalesces, the final committed state is
 * always eventually snapshotted.
 */
class DriveBackupScheduler(
    private val scope: CoroutineScope,
    private val backupFn: suspend () -> Unit,
    private val debounceMs: Long = 1500L,
) {
    private val mutex = Mutex()
    private var pendingJob: Job? = null
    private val lock = Any()

    /** Fire-and-forget: safe to call after any store write. Never throws. */
    fun requestBackup() {
        try {
            synchronized(lock) {
                pendingJob?.cancel()
                pendingJob = scope.launch {
                    try {
                        if (debounceMs > 0) delay(debounceMs)
                    } catch (_: Exception) {
                        return@launch // superseded by a newer request
                    }
                    runBackupSingleFlight()
                }
            }
        } catch (_: Exception) {
            // Scheduler must never break protection writes.
        }
    }

    /** Immediate flush, e.g. for tests or foreground reconciliation. Coalesced. */
    suspend fun flushNow() {
        synchronized(lock) { pendingJob?.cancel(); pendingJob = null }
        runBackupSingleFlight()
    }

    private suspend fun runBackupSingleFlight() {
        // Single-flight: concurrent triggers converge on one execution.
        mutex.withLock {
            try {
                backupFn()
            } catch (_: Exception) {
                // Backup failure is logged by the manager; never propagated.
            }
        }
    }
}
