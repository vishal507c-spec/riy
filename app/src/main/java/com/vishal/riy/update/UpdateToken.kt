package com.vishal.riy.update

import com.vishal.riy.BuildConfig

/**
 * Supplies the Fine-grained GitHub PAT used to read the PRIVATE repository
 * releases and assets. The value is injected at build time from LOCAL config
 * only (env var / Gradle property / local.properties) — it is never committed,
 * never hard-coded, and never in strings.xml or the AndroidManifest.
 *
 * Security: the field is blank in RELEASE builds, so a production artifact
 * carries no token. Only local DEBUG (test) builds embed it.
 */
object UpdateToken {

    /** True when a token was supplied via local config. */
    val isConfigured: Boolean
        get() = BuildConfig.GITHUB_TOKEN.isNotBlank()

    /** The token value, or null when not configured. */
    val value: String?
        get() = BuildConfig.GITHUB_TOKEN.takeIf { it.isNotBlank() }
}
