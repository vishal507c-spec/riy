package com.vishal.riy.drive

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger

/**
 * Tests for the debounced single-flight scheduler. Uses the test dispatcher
 * so debounce windows advance deterministically.
 */
class DriveBackupSchedulerTest {

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun `rapid requests coalesce into one backup`() = runTest {
        val calls = AtomicInteger(0)
        val s = DriveBackupScheduler(this, { calls.incrementAndGet() }, debounceMs = 1500L)
        s.requestBackup()
        s.requestBackup()
        s.requestBackup()
        assertEquals(0, calls.get())
        advanceTimeBy(1600L)
        assertEquals(1, calls.get())
    }

    @Test
    fun `flushNow runs immediately and coalesces`() = runBlocking {
        val calls = AtomicInteger(0)
        val s = DriveBackupScheduler(this, { calls.incrementAndGet() }, debounceMs = 60_000L)
        s.requestBackup()
        s.flushNow()
        assertEquals(1, calls.get())
    }

    @Test
    fun `requestBackup never throws without an installed scope failure`() {
        val s = DriveBackupScheduler(
            kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.Unconfined),
            { throw RuntimeException("boom") },
            debounceMs = 0L,
        )
        // Must not throw synchronously.
        s.requestBackup()
    }
}
