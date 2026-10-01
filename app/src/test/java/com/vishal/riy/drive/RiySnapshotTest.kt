package com.vishal.riy.drive

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests for the canonical riy snapshot codec: determinism, typed round-trip,
 * validation rejections and reconcile policy.
 */
class RiySnapshotTest {

    private fun build(gen: Long = 3L): ByteArray = RiySnapshot.build(
        stores = mapOf(
            "riy_lock_prefs" to mapOf("lock_state_v1" to "10|20|x.example"),
            "blocker_state_prefs" to mapOf("protection_wanted" to true),
            "riy_escalation_state_prefs" to mapOf("escalation_state_v1" to "1|1,2"),
            "riy_protection_state_prefs" to mapOf("n" to 5, "l" to 1234567890123L, "f" to 1.5f),
            "riy_protection_events_prefs" to mapOf("k" to setOf("b", "a")),
        ),
        snapshotId = "snap-1",
        generation = gen,
        createdAt = 1700000000000L,
        appVersion = "2.2.1",
    )

    @Test
    fun `build is deterministic for identical input`() {
        assertEquals(
            Hashing.sha256Hex(build()),
            Hashing.sha256Hex(build()),
        )
    }

    @Test
    fun `generation change changes bytes`() {
        assertFalse(Hashing.sha256Hex(build(3L)) == Hashing.sha256Hex(build(4L)))
    }

    @Test
    fun `parse round-trips typed values exactly`() {
        val decoded = RiySnapshot.parse(build())!!
        assertEquals("snap-1", decoded.snapshotId)
        assertEquals(3L, decoded.generation)
        assertEquals("10|20|x.example", decoded.stores["riy_lock_prefs"]!!["lock_state_v1"]!!.toValue())
        assertEquals(true, decoded.stores["blocker_state_prefs"]!!["protection_wanted"]!!.toValue())
        assertEquals(5, decoded.stores["riy_protection_state_prefs"]!!["n"]!!.toValue())
        assertEquals(1234567890123L, decoded.stores["riy_protection_state_prefs"]!!["l"]!!.toValue())
        assertEquals(1.5f, decoded.stores["riy_protection_state_prefs"]!!["f"]!!.toValue())
        assertEquals(setOf("a", "b"), decoded.stores["riy_protection_events_prefs"]!!["k"]!!.toValue())
        // payloadSha256 binds the exact input bytes.
        assertEquals(Hashing.sha256Hex(build()), decoded.payloadSha256)
    }

    @Test
    fun `unknown store files and bad values are skipped safely`() {
        val bytes = RiySnapshot.build(
            stores = mapOf("nope_prefs" to mapOf("k" to "v"), "riy_lock_prefs" to emptyMap<String, Any>()),
            snapshotId = "s", generation = 0L, createdAt = 0L, appVersion = "x",
        )
        val decoded = RiySnapshot.parse(bytes)!!
        assertTrue(decoded.stores["nope_prefs"] == null)
        assertTrue(decoded.stores["riy_lock_prefs"]!!.isEmpty())
    }

    @Test
    fun `malformed snapshots are rejected`() {
        assertNull(RiySnapshot.parse("{ not json".toByteArray()))
        assertNull(RiySnapshot.parse("{}".toByteArray()))
        assertNull(RiySnapshot.parse("{\"formatVersion\":99}".toByteArray()))
        // missing generation
        assertNull(
            RiySnapshot.parse(
                "{\"formatVersion\":1,\"snapshotId\":\"s\",\"createdAt\":0,\"appVersion\":\"x\",\"stores\":{}}".toByteArray(),
            ),
        )
    }

    @Test
    fun `isEmptyContent detects fresh installs`() {
        val empty = RiySnapshot.parse(
            RiySnapshot.build(emptyMap(), "s", 0L, 0L, "x"),
        )!!
        assertTrue(RiySnapshot.isEmptyContent(empty))
        assertFalse(RiySnapshot.isEmptyContent(RiySnapshot.parse(build())!!))
    }

    @Test
    fun `reconcile policy matrix`() {
        val a = SnapshotIdentity("aaa", 5L)
        val b = SnapshotIdentity("bbb", 5L)
        val c = SnapshotIdentity("aaa", 5L)
        assertEquals(ReconcileDecision.NOTHING_TRUSTWORTHY, ReconcilePolicy.decide(null, null))
        assertEquals(ReconcileDecision.LOCAL_TO_DRIVE, ReconcilePolicy.decide(a, null))
        assertEquals(ReconcileDecision.DRIVE_TO_LOCAL, ReconcilePolicy.decide(null, a))
        assertEquals(ReconcileDecision.ALREADY_SYNCED, ReconcilePolicy.decide(a, c))
        // Same generation, different content → preserve both, never overwrite.
        assertEquals(ReconcileDecision.CONFLICT_PRESERVE_BOTH, ReconcilePolicy.decide(a, b))
        assertEquals(
            ReconcileDecision.LOCAL_NEWER_TO_DRIVE,
            ReconcilePolicy.decide(SnapshotIdentity("x", 6L), SnapshotIdentity("y", 5L)),
        )
        assertEquals(
            ReconcileDecision.DRIVE_NEWER_TO_LOCAL,
            ReconcilePolicy.decide(SnapshotIdentity("x", 4L), SnapshotIdentity("y", 5L)),
        )
    }
}
