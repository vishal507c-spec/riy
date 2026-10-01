package com.vishal.riy.drive

/**
 * Global fire-and-forget hook that protection stores call after every write.
 *
 * The scheduler is installed once from MainActivity (`install`). Until then —
 * and if anything ever fails — [requestBackup] is a safe no-op, so protection
 * writes can never break because of Drive. This mirrors the reference wiring
 * where repositories call `autoBackup.requestBackup()` after each commit.
 */
object DriveSync {

    @Volatile private var scheduler: DriveBackupScheduler? = null

    fun install(s: DriveBackupScheduler) {
        scheduler = s
    }

    /** Coalesced backup request. Never throws. */
    fun requestBackup() {
        try {
            scheduler?.requestBackup()
        } catch (_: Exception) {
            // Drive must never break protection writes.
        }
    }
}
