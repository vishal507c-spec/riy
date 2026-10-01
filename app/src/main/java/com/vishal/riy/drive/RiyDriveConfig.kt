package com.vishal.riy.drive

import com.vishal.riy.BuildConfig

/**
 * Deployment-time configuration for the Drive backup destination. Injected at
 * build time via Gradle (BuildConfig) — it holds NO secrets (no passwords,
 * tokens or client secrets in the APK). The Google account identity is
 * obtained at runtime through the secretless Android OAuth flow and remembered
 * afterwards.
 *
 * Cloned from the reference `RemoteBackupConfig` (same shape; folder renamed
 * to keep riy data isolated from the reference app's `UnitBackup`).
 */
object RiyDriveConfig {

    /** Whether Drive backup is enabled for this build. */
    val enabled: Boolean = BuildConfig.REMOTE_BACKUP_ENABLED

    /** The dedicated Drive folder riy operates in. */
    val folder: String = BuildConfig.DRIVE_FOLDER
}
