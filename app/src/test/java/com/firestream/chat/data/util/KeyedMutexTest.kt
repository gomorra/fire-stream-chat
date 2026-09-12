package com.firestream.chat.data.util

import kotlinx.coroutines.delay
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.max

class KeyedMutexTest {

    private val locks = KeyedMutex<String>()

    /** Runs [count] holders of [key] at once and reports how many were ever inside together. */
    private suspend fun kotlinx.coroutines.CoroutineScope.overlapOf(vararg keys: String): Int {
        var inside = 0
        var mostInside = 0
        keys.map { key ->
            launch {
                locks.withLock(key) {
                    inside++
                    mostInside = max(mostInside, inside)
                    delay(10)
                    inside--
                }
            }
        }.joinAll()
        return mostInside
    }

    @Test
    fun `holders of one key run one at a time`() = runTest {
        assertEquals(1, overlapOf("a", "a", "a", "a"))
    }

    @Test
    fun `holders of different keys run together`() = runTest {
        assertEquals(2, overlapOf("a", "b"))
    }

    @Test
    fun `a key's lock exists only while somebody holds or waits for it`() = runTest {
        locks.withLock("a") { assertTrue(locks.isTracked("a")) }

        assertFalse(locks.isTracked("a"))
    }

    @Test
    fun `a lock is dropped even when the block throws`() = runTest {
        runCatching { locks.withLock("a") { throw IllegalStateException("boom") } }

        assertFalse(locks.isTracked("a"))
    }
}
