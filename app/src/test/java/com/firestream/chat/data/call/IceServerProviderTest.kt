package com.firestream.chat.data.call

import com.firestream.chat.data.remote.source.IceServerSource
import com.firestream.chat.domain.model.IceServerData
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test
import java.io.IOException

/**
 * The provider runs its fetch on the test's background scope, on the test's virtual clock, which
 * is also the clock it dates a kept set with.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class IceServerProviderTest {

    private val relay = listOf(
        IceServerData(listOf("stun:stun.cloudflare.com:3478")),
        IceServerData(listOf("turn:turn.cloudflare.com:3478?transport=udp"), "user", "secret")
    )

    /** Answers with [answer] after [delayMs], or throws [failure]. Counts how often it was asked. */
    private class FakeSource(
        var answer: List<IceServerData>,
        var delayMs: Long = 0,
        var failure: Exception? = null
    ) : IceServerSource {
        var fetches = 0

        override suspend fun fetchIceServers(): List<IceServerData> {
            fetches++
            delay(delayMs)
            failure?.let { throw it }
            return answer
        }
    }

    private fun TestScope.provider(source: IceServerSource) =
        IceServerProvider(source, backgroundScope) { currentTime }

    @Test
    fun `a fetched set is kept and not fetched again`() = runTest {
        val source = FakeSource(relay)
        val provider = provider(source)

        assertEquals(relay, provider.get())
        advanceTimeBy(IceServerProvider.KEEP_MS - 1)
        assertEquals(relay, provider.get())

        assertEquals(1, source.fetches)
        assertEquals(relay, provider.current())
    }

    @Test
    fun `a set older than twelve hours is fetched again`() = runTest {
        val source = FakeSource(relay)
        val provider = provider(source)
        provider.get()

        advanceTimeBy(IceServerProvider.KEEP_MS)
        // Too old to build a connection on, even for the caller who cannot wait.
        assertEquals(IceServerProvider.STUN_ONLY, provider.current())
        provider.get()

        assertEquals(2, source.fetches)
    }

    @Test
    fun `an answer that takes too long gives STUN only after three seconds`() = runTest {
        val source = FakeSource(relay, delayMs = 10_000)
        val provider = provider(source)

        assertEquals(IceServerProvider.STUN_ONLY, provider.get())

        assertEquals(3_000, currentTime)
    }

    // The caller stopped waiting, the fetch did not stop. The next call has the relay.
    @Test
    fun `an answer that arrives after the wait is kept for the next call`() = runTest {
        val source = FakeSource(relay, delayMs = 10_000)
        val provider = provider(source)
        provider.get()

        advanceTimeBy(7_001)

        assertEquals(relay, provider.current())
        assertEquals(relay, provider.get())
        assertEquals(1, source.fetches)
    }

    @Test
    fun `a failed fetch gives STUN only`() = runTest {
        val provider = provider(FakeSource(relay, failure = IOException("unavailable")))

        assertEquals(IceServerProvider.STUN_ONLY, provider.get())
        assertEquals(IceServerProvider.STUN_ONLY, provider.current())
    }

    // The backend's client can cancel its own request. The caller was not cancelled.
    @Test
    fun `a request cancelled by the backend's client gives STUN only`() = runTest {
        val provider = provider(FakeSource(relay, failure = CancellationException("task cancelled")))

        assertEquals(IceServerProvider.STUN_ONLY, provider.get())
    }

    // The pocketbase backend, or a function that answered without a usable entry.
    @Test
    fun `a backend without a relay gives STUN only`() = runTest {
        val provider = provider(FakeSource(emptyList()))

        assertEquals(IceServerProvider.STUN_ONLY, provider.get())
    }

    // The ring fetches, the answer asks again a moment later. It must not wait a second time.
    @Test
    fun `a failure is not asked again for a minute, and is asked again after it`() = runTest {
        val source = FakeSource(relay, delayMs = 2_000, failure = IOException("unavailable"))
        val provider = provider(source)
        provider.get()
        val failedAt = currentTime

        assertEquals(IceServerProvider.STUN_ONLY, provider.get())
        assertEquals(failedAt, currentTime)
        assertEquals(1, source.fetches)
        // The side that answers opens its session on this at once.
        assertEquals(IceServerProvider.STUN_ONLY, provider.settled())

        advanceTimeBy(IceServerProvider.RETRY_AFTER_MS)
        assertNull(provider.settled())
        source.failure = null
        assertEquals(relay, provider.get())
        assertEquals(2, source.fetches)
    }

    @Test
    fun `two callers at the same time share one fetch`() = runTest {
        val source = FakeSource(relay, delayMs = 1_000)
        val provider = provider(source)

        val first = async { provider.get() }
        val second = async { provider.get() }

        assertEquals(relay, first.await())
        assertEquals(relay, second.await())
        assertEquals(1, source.fetches)
    }

    @Test
    fun `nothing is kept before the first fetch, and warming fills it without waiting`() = runTest {
        val source = FakeSource(relay, delayMs = 500)
        val provider = provider(source)
        assertEquals(IceServerProvider.STUN_ONLY, provider.current())

        provider.warm()
        runCurrent()
        assertEquals(0, currentTime)
        assertEquals(IceServerProvider.STUN_ONLY, provider.current())
        assertNull(provider.settled())

        advanceTimeBy(501)
        assertEquals(relay, provider.current())
        assertEquals(relay, provider.settled())
    }

    @Test
    fun `an entry printed in a log line does not show its login`() {
        val printed = relay.toString()

        assertFalse(printed.contains("secret"))
        assertFalse(printed.contains("user"))
    }
}
