package com.vishal.riy.drive

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.IOException

/**
 * Tests for the folder resolve policy: validate-then-trust cache,
 * search-before-create, deterministic reuse, create-then-verify.
 */
class DriveFolderResolverTest {

    private class FakeGateway(
        var folders: MutableList<String> = mutableListOf(),
        var failFind: Boolean = false,
        var failCreate: Boolean = false,
        var timeoutCreateOnce: Boolean = false,
    ) : DriveFolderGateway {
        var creates = 0
        var nextId = 100

        override suspend fun findFolders(name: String): List<String> {
            if (failFind) throw IOException("offline")
            return folders.toList()
        }

        override suspend fun createFolder(name: String): String {
            creates++
            if (failCreate) throw IOException("denied")
            if (timeoutCreateOnce) {
                timeoutCreateOnce = false
                // Simulate a timeout AFTER the folder was actually created.
                val id = "id-${nextId++}"
                folders += id
                throw IOException("timeout")
            }
            val id = "id-${nextId++}"
            folders += id
            return id
        }

        override suspend fun isValidFolder(id: String): Boolean = folders.contains(id)
    }

    @Test
    fun `search before create — existing folder reused, no create`() = runBlocking {
        val gw = FakeGateway(mutableListOf("id-1"))
        val r = DriveFolderResolver("RiyBackup", gw)
        assertEquals("id-1", r.locate())
        assertEquals(0, gw.creates)
    }

    @Test
    fun `missing folder is created exactly once then verified`() = runBlocking {
        val gw = FakeGateway()
        val r = DriveFolderResolver("RiyBackup", gw)
        val id = r.locate()
        assertTrue(id.startsWith("id-"))
        assertEquals(1, gw.creates)
        // Second locate uses the validated cache — no second create.
        assertEquals(id, r.locate())
        assertEquals(1, gw.creates)
    }

    @Test
    fun `multiple folders reuse the deterministic first, never create`() = runBlocking {
        val gw = FakeGateway(mutableListOf("id-9", "id-3"))
        val r = DriveFolderResolver("RiyBackup", gw)
        assertEquals("id-9", r.locate())
        assertEquals(0, gw.creates)
    }

    @Test
    fun `invalid cached id auto-heals by re-searching`() = runBlocking {
        val gw = FakeGateway(mutableListOf("id-7"))
        val r = DriveFolderResolver("RiyBackup", gw, persistedId = "id-deleted")
        assertEquals("id-7", r.locate())
        assertEquals(0, gw.creates)
    }

    @Test
    fun `create timeout re-searches instead of duplicating`() = runBlocking {
        val gw = FakeGateway(timeoutCreateOnce = true)
        val r = DriveFolderResolver("RiyBackup", gw)
        val id = r.locate()
        assertEquals(1, gw.creates)
        // The timed-out create actually landed; resolver found it via re-search.
        assertEquals(id, gw.folders.single())
        assertEquals(id, r.locate())
        assertEquals(1, gw.creates)
    }

    @Test
    fun `create failure surfaces instead of inventing an id`() = runBlocking {
        val gw = FakeGateway(failCreate = true)
        val r = DriveFolderResolver("RiyBackup", gw)
        try {
            r.locate()
            fail("expected IOException")
        } catch (_: IOException) {
            // expected — unknown result is never treated as success
        }
    }

    @Test
    fun `reset drops cache and re-searches`() = runBlocking {
        val gw = FakeGateway(mutableListOf("id-1"))
        val r = DriveFolderResolver("RiyBackup", gw, persistedId = "id-1")
        assertEquals("id-1", r.locate())
        r.reset()
        gw.folders.clear()
        gw.folders += "id-2"
        assertEquals("id-2", r.locate())
    }
}
