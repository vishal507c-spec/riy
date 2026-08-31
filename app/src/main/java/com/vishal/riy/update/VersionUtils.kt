package com.vishal.riy.update

/**
 * Pure versioning math used by the auto-update feature.
 *
 * Strategy: versionName is a MAJOR.MINOR.PATCH string (as in the git tag
 * "v1.0.10"); versionCode is derived numerically as
 *
 *     major * 1_000_000 + minor * 1_000 + patch
 *
 * so ordering is numeric and correct (1.0.10 -> 1_001_010 > 1.0.9 -> 1_000_009).
 * This mirrors the Gradle logic in app/build.gradle.kts — KEEP THE TWO IN SYNC.
 */
object VersionUtils {

    const val MAX_SEGMENT = 999L
    const val DEFAULT_VERSION_NAME = "1.0.0"
    const val DEFAULT_VERSION_CODE = 1_000_000L

    /** Strips an optional leading "v"/"V" from a release tag ("v1.0.5" -> "1.0.5"). */
    fun stripTagPrefix(tag: String): String =
        if (tag.length > 1 && (tag[0] == 'v' || tag[0] == 'V')) tag.substring(1) else tag

    /**
     * Parses "MAJOR.MINOR.PATCH" (with optional v/V prefix) into a numeric
     * versionCode. Returns null for anything malformed instead of guessing.
     */
    fun versionCodeFromName(versionName: String): Long? {
        val name = stripTagPrefix(versionName.trim())
        val parts = name.split(".")
        if (parts.size != 3) return null
        val numbers = LongArray(3)
        for (i in 0..2) {
            val n = parts[i].toLongOrNull() ?: return null
            if (n < 0 || n > MAX_SEGMENT) return null
            numbers[i] = n
        }
        return numbers[0] * 1_000_000L + numbers[1] * 1_000L + numbers[2]
    }

    /**
     * Numeric-semantic comparison of two "MAJOR.MINOR.PATCH" names.
     * Returns negative if [a] < [b], 0 if equal, positive if [a] > [b].
     * Falls back to segment-wise comparison for names with >3 segments so
     * "1.0.0.1" still orders after "1.0.0" rather than comparing as strings.
     */
    fun compareVersionNames(a: String, b: String): Int {
        val pa = stripTagPrefix(a.trim()).split(".").map { it.toLongOrNull() ?: 0L }
        val pb = stripTagPrefix(b.trim()).split(".").map { it.toLongOrNull() ?: 0L }
        val len = maxOf(pa.size, pb.size)
        for (i in 0 until len) {
            val x = pa.getOrElse(i) { 0L }
            val y = pb.getOrElse(i) { 0L }
            if (x != y) return x.compareTo(y)
        }
        return 0
    }
}
