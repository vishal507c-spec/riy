package com.vishal.riy.drive

import java.security.MessageDigest

/**
 * SHA-256 identity for every Drive payload — the authority for upload
 * idempotency, download verification and conflict comparison.
 *
 * Cloned from the reference system's `Hashing`: pure JVM, no Android
 * dependency, so every rule is unit-testable on the desktop JVM.
 */
object Hashing {

    /** Lowercase hex SHA-256 of [bytes]. */
    fun sha256Hex(bytes: ByteArray): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(bytes)
        return buildString(digest.size * 2) {
            for (b in digest) {
                val v = b.toInt() and 0xFF
                if (v < 0x10) append('0')
                append(v.toString(16))
            }
        }
    }

    /** Lowercase hex SHA-256 of [text] in UTF-8. */
    fun sha256Hex(text: String): String = sha256Hex(text.toByteArray(Charsets.UTF_8))

    /** Short identity prefix (default 8 chars) for logs — never a full dataset. */
    fun short(bytes: ByteArray, len: Int = 8): String = sha256Hex(bytes).take(len)
}
