package com.firestream.chat.data.util

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger

class SingleFlightTest {

    @Test
    fun `concurrent callers for one key run the block once`() = runBlocking {
        val singleFlight = SingleFlight<String, Int>()
        val runs = AtomicInteger()
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()

        val first = async {
            singleFlight.run("k") {
                runs.incrementAndGet()
                entered.complete(Unit)
                release.await()
                42
            }
        }
        entered.await()
        val second = async { singleFlight.run("k") { runs.incrementAndGet(); 99 } }
        release.complete(Unit)

        assertEquals(42, first.await())
        // The second caller joins the in-flight run rather than starting its own.
        assertEquals(42, second.await())
        assertEquals(1, runs.get())
    }

    @Test
    fun `different keys run independently`() = runBlocking {
        val singleFlight = SingleFlight<String, String>()
        assertEquals("a", singleFlight.run("a") { "a" })
        assertEquals("b", singleFlight.run("b") { "b" })
    }

    @Test
    fun `a finished key is not retained so the next caller runs again`() = runBlocking {
        val singleFlight = SingleFlight<String, Int>()
        val runs = AtomicInteger()

        singleFlight.run("k") { runs.incrementAndGet() }
        singleFlight.run("k") { runs.incrementAndGet() }

        // Caching finished results is the caller's policy, not this helper's.
        assertEquals(2, runs.get())
    }

    @Test
    fun `a failure propagates and leaves nothing behind`() = runBlocking {
        val singleFlight = SingleFlight<String, Int>()

        assertThrows(IllegalStateException::class.java) {
            runBlocking { singleFlight.run("k") { throw IllegalStateException("boom") } }
        }

        // The next caller retries instead of inheriting the stale exception.
        assertEquals(7, singleFlight.run("k") { 7 })
    }

    @Test
    fun `a waiter sees the failure of the run it joined`() = runBlocking {
        val singleFlight = SingleFlight<String, Int>()
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()

        val first = async {
            runCatching {
                singleFlight.run("k") {
                    entered.complete(Unit)
                    release.await()
                    throw IllegalStateException("boom")
                }
            }
        }
        entered.await()
        val second = async { runCatching { singleFlight.run("k") { 1 } } }
        release.complete(Unit)

        assertEquals("boom", first.await().exceptionOrNull()?.message)
        assertEquals("boom", second.await().exceptionOrNull()?.message)
    }

    @Test
    fun `a waiter runs the block itself when the first caller is cancelled`() = runBlocking {
        val singleFlight = SingleFlight<String, Int>()
        val entered = CompletableDeferred<Unit>()

        // A cell that scrolls away while its download is running.
        val first = launch {
            singleFlight.run("k") {
                entered.complete(Unit)
                awaitCancellation()
            }
        }
        entered.await()
        val second = async { singleFlight.run("k") { 7 } }
        yield()
        first.cancelAndJoin()

        // The first caller's cancellation is not the waiter's.
        assertEquals(7, second.await())
    }

    @Test
    fun `a waiter that is cancelled itself stops waiting`() = runBlocking {
        val singleFlight = SingleFlight<String, Int>()
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val runs = AtomicInteger()

        val first = async {
            singleFlight.run("k") {
                entered.complete(Unit)
                release.await()
                42
            }
        }
        entered.await()
        val second = launch { singleFlight.run("k") { runs.incrementAndGet() } }
        yield()
        second.cancelAndJoin()
        release.complete(Unit)

        assertEquals(42, first.await())
        assertEquals("the cancelled waiter never ran the block", 0, runs.get())
    }
}
