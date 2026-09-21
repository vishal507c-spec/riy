package com.vishal.riy.protection.state

/**
 * Durable storage for the current [ProtectionSession].
 *
 * The store is platform-independent by design: it speaks only pure-Kotlin
 * types, so the same contract backs a SharedPreferences implementation in
 * production and an [InMemoryProtectionStateStore] in unit tests.
 *
 * A session persisted here must survive Activity recreation, process death,
 * an app restart and a normal device reboot.
 *
 * Phase 2 establishes the contract only; persistence arrives later.
 */
interface ProtectionStateStore {

    /** The persisted session, or null if none was ever recorded (or it cleared). */
    fun load(): ProtectionSession?

    /** Persists [session] atomically. */
    fun save(session: ProtectionSession)

    /** Removes any persisted session. */
    fun clear()

    /**
     * The session as it is currently understood in-memory by this store
     * (cached/refreshed view of [load]). Implementations may simply delegate
     * to [load].
     */
    fun current(): ProtectionSession?
}
