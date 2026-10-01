package com.vishal.riy.drive

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests for the VAULT publication state machine using [InMemoryRemoteStore]:
 * candidate → verify → promote → confirm, idempotency, anti-rollback and
 * same-generation conflict preservation.
 */
class RemoteCoordinatorTest {

    private fun snap(gen: Long, marker: String = "data"): ByteArray = RiySnapshot.build(
        stores = mapOf("riy_lock_prefs" to mapOf("k" to marker)),
        snapshotId = "s", generation = gen, createdAt = 1000L, appVersion = "t",
    )

    @Test
    fun `first publish promotes and writes pointer`() = runBlocking {
        val store = InMemoryRemoteStore()
        val rc = RemoteCoordinator(store, "test")
        val bytes = snap(1L)
        val sha = Hashing.sha256Hex(bytes)
        assertEquals(
            UploadOutcome.VERIFIED_AND_PROMOTED,
            rc.sync(SnapshotIdentity(sha, 1L), bytes, 1000L),
        )
        assertEquals(sha, Hashing.sha256Hex(store.raw("VAULT")!!))
        assertFalse(store.exists("VAULT.candidate"))
        // Pointer accelerator exists and resolves to generation 1.
        assertEquals(1L, LatestPointer.from(store.raw("_latest_verified.json"))!!.generation)
    }

    @Test
    fun `same content twice is idempotent with zero effective change`() = runBlocking {
        val store = InMemoryRemoteStore()
        val rc = RemoteCoordinator(store, "test")
        val bytes = snap(1L)
        val sha = Hashing.sha256Hex(bytes)
        rc.sync(SnapshotIdentity(sha, 1L), bytes, 1000L)
        assertEquals(
            UploadOutcome.ALREADY_VERIFIED,
            rc.sync(SnapshotIdentity(sha, 1L), bytes, 1000L),
        )
        assertEquals(sha, Hashing.sha256Hex(store.raw("VAULT")!!))
    }

    @Test
    fun `claimed identity mismatch never uploads`() = runBlocking {
        val store = InMemoryRemoteStore()
        val rc = RemoteCoordinator(store, "test")
        val bytes = snap(1L)
        assertEquals(
            UploadOutcome.FAILED,
            rc.sync(SnapshotIdentity("0".repeat(64), 1L), bytes, 1000L),
        )
        assertFalse(store.exists("VAULT"))
        assertFalse(store.exists("VAULT.candidate"))
    }

    @Test
    fun `older generation never demotes the VAULT`() = runBlocking {
        val store = InMemoryRemoteStore()
        val rc = RemoteCoordinator(store, "test")
        val newer = snap(5L, "newer")
        val newerSha = Hashing.sha256Hex(newer)
        rc.sync(SnapshotIdentity(newerSha, 5L), newer, 1000L)
        val older = snap(3L, "older")
        val olderSha = Hashing.sha256Hex(older)
        assertEquals(
            UploadOutcome.VERIFIED_NO_PROMOTION,
            rc.sync(SnapshotIdentity(olderSha, 3L), older, 2000L),
        )
        assertEquals(newerSha, Hashing.sha256Hex(store.raw("VAULT")!!))
        assertFalse(store.exists("VAULT.candidate"))
    }

    @Test
    fun `same generation different content never flips the VAULT`() = runBlocking {
        val store = InMemoryRemoteStore()
        val rc = RemoteCoordinator(store, "test")
        val first = snap(2L, "first")
        val firstSha = Hashing.sha256Hex(first)
        rc.sync(SnapshotIdentity(firstSha, 2L), first, 1000L)
        val second = snap(2L, "second")
        val secondSha = Hashing.sha256Hex(second)
        assertEquals(
            UploadOutcome.VERIFIED_NO_PROMOTION,
            rc.sync(SnapshotIdentity(secondSha, 2L), second, 2000L),
        )
        assertEquals(firstSha, Hashing.sha256Hex(store.raw("VAULT")!!))
    }

    @Test
    fun `corrupted candidate fails and leaves old VAULT authoritative`() = runBlocking {
        val store = InMemoryRemoteStore()
        val rc = RemoteCoordinator(store, "test")
        val good = snap(1L, "good")
        val goodSha = Hashing.sha256Hex(good)
        rc.sync(SnapshotIdentity(goodSha, 1L), good, 1000L)
        store.corruptOnGet = "VAULT.candidate"
        val next = snap(2L, "next")
        val nextSha = Hashing.sha256Hex(next)
        assertEquals(
            UploadOutcome.FAILED,
            rc.sync(SnapshotIdentity(nextSha, 2L), next, 2000L),
        )
        assertEquals(goodSha, Hashing.sha256Hex(store.raw("VAULT")!!))
    }

    @Test
    fun `put failure fails without destroying VAULT`() = runBlocking {
        val store = InMemoryRemoteStore()
        val rc = RemoteCoordinator(store, "test")
        store.failPut = true
        val bytes = snap(1L)
        assertEquals(
            UploadOutcome.FAILED,
            rc.sync(SnapshotIdentity(Hashing.sha256Hex(bytes), 1L), bytes, 1000L),
        )
        assertFalse(store.exists("VAULT"))
    }

    @Test
    fun `sweepStaging removes only candidates`() = runBlocking {
        val store = InMemoryRemoteStore()
        val rc = RemoteCoordinator(store, "test")
        val bytes = snap(1L)
        store.seed("VAULT", bytes)
        store.seed("VAULT.candidate", bytes)
        store.seed("other.candidate", bytes)
        val removed = rc.sweepStaging()
        assertTrue(removed.contains("VAULT.candidate"))
        assertTrue(removed.contains("other.candidate"))
        assertTrue(store.exists("VAULT"))
    }

    @Test
    fun `pointer never moves backwards`() = runBlocking {
        val store = InMemoryRemoteStore()
        val rc = RemoteCoordinator(store, "test")
        val bytes = snap(4L)
        rc.sync(SnapshotIdentity(Hashing.sha256Hex(bytes), 4L), bytes, 1000L)
        assertTrue(rc.repairStalePointer(2000L))
        assertEquals(4L, LatestPointer.from(store.raw("_latest_verified.json"))!!.generation)
    }

    @Test
    fun `reverify detects replacement`() = runBlocking {
        val store = InMemoryRemoteStore()
        val rc = RemoteCoordinator(store, "test")
        val bytes = snap(1L)
        val sha = Hashing.sha256Hex(bytes)
        rc.sync(SnapshotIdentity(sha, 1L), bytes, 1000L)
        assertTrue(rc.reverify(SnapshotIdentity(sha, 1L)))
        store.seed("VAULT", snap(1L, "tampered"))
        assertFalse(rc.reverify(SnapshotIdentity(sha, 1L)))
    }
}
