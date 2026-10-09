package com.firestream.chat.data.call

import com.firestream.chat.domain.repository.CallRepository
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.coVerifyOrder
import io.mockk.mockk
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import org.junit.After
import org.junit.Before
import org.junit.Test

/**
 * When a call's document is written to. The scope runs on an unconfined dispatcher, so every write
 * has been made by the time the call that caused it returns.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class CallMediaPublisherTest {

    private val repository: CallRepository = mockk()
    private val scope = CoroutineScope(UnconfinedTestDispatcher() + SupervisorJob())
    private val publisher = CallMediaPublisher(repository, scope)

    @Before
    fun setUp() {
        coEvery { repository.setMedia(any(), any(), any()) } returns Result.success(Unit)
    }

    @After
    fun tearDown() {
        scope.cancel()
    }

    private fun verifyNothingWritten() {
        coVerify(exactly = 0) { repository.setMedia(any(), any(), any()) }
    }

    // ── The gate ─────────────────────────────────────────────────────────────

    // Regression: a released app that placed the call applies the answer again on every change of
    // an answered call document, and ends the call when that fails. It never agrees on a video
    // line, so a call with it must never be written to.
    @Test
    fun `a call without an agreed video line is never written to`() {
        publisher.begin("call1")
        publisher.onVideoLine("call1", agreed = false)
        publisher.onConnected("call1")

        publisher.update { it.copy(mic = false) }
        publisher.update { it.copy(camera = true) }
        // A connection that dropped and came back.
        publisher.onConnected("call1")

        verifyNothingWritten()
    }

    @Test
    fun `nothing is written before the call connects`() {
        publisher.begin("call1")
        publisher.onVideoLine("call1", agreed = true)

        publisher.update { it.copy(mic = false) }

        verifyNothingWritten()
    }

    @Test
    fun `nothing is written before the video line is known`() {
        publisher.begin("call1")
        publisher.onConnected("call1")

        publisher.update { it.copy(mic = false) }

        verifyNothingWritten()
    }

    @Test
    fun `writing starts on connect with the state as it is by then`() {
        publisher.begin("call1")
        publisher.onVideoLine("call1", agreed = true)
        publisher.update { it.copy(mic = false) }

        publisher.onConnected("call1")

        coVerify(exactly = 1) { repository.setMedia("call1", camera = false, mic = false) }
    }

    @Test
    fun `writing starts with the video line when the call connected first`() {
        publisher.begin("call1")
        publisher.onConnected("call1")

        publisher.onVideoLine("call1", agreed = true)

        coVerify(exactly = 1) { repository.setMedia("call1", camera = false, mic = true) }
    }

    // ── While writing ────────────────────────────────────────────────────────

    private fun writing(callId: String = "call1") {
        publisher.begin(callId)
        publisher.onVideoLine(callId, agreed = true)
        publisher.onConnected(callId)
    }

    @Test
    fun `every change is written, in order`() {
        writing()

        publisher.update { it.copy(camera = true) }
        publisher.update { it.copy(mic = false) }

        coVerifyOrder {
            repository.setMedia("call1", camera = false, mic = true)
            repository.setMedia("call1", camera = true, mic = true)
            repository.setMedia("call1", camera = true, mic = false)
        }
    }

    @Test
    fun `a change that changes nothing is not written again`() {
        writing()

        publisher.update { it.copy(camera = false) }

        coVerify(exactly = 1) { repository.setMedia(any(), any(), any()) }
    }

    @Test
    fun `a connection that comes back does not start a second writer`() {
        writing()

        publisher.onConnected("call1")
        publisher.update { it.copy(camera = true) }

        coVerify(exactly = 1) { repository.setMedia("call1", camera = true, mic = true) }
    }

    @Test
    fun `a write that fails does not stop the next one`() {
        coEvery { repository.setMedia("call1", camera = false, mic = true) } returns
            Result.failure(IllegalStateException("offline"))
        writing()

        publisher.update { it.copy(camera = true) }

        coVerify(exactly = 1) { repository.setMedia("call1", camera = true, mic = true) }
    }

    // ── The end of a call, and the next one ──────────────────────────────────

    @Test
    fun `nothing is written after the call ended`() {
        writing()
        publisher.end()
        publisher.end()

        publisher.update { it.copy(camera = true) }

        coVerify(exactly = 1) { repository.setMedia(any(), any(), any()) }
    }

    @Test
    fun `an event of a call that is over does not open the next call to writes`() {
        writing("call1")
        publisher.begin("call2")

        // Still on their way when call1 ended.
        publisher.onVideoLine("call1", agreed = true)
        publisher.onConnected("call1")
        publisher.update { it.copy(camera = true) }

        coVerify(exactly = 0) { repository.setMedia("call2", any(), any()) }
        coVerify(exactly = 1) { repository.setMedia("call1", any(), any()) }
    }

    @Test
    fun `an event before any call does nothing`() {
        publisher.onVideoLine("call1", agreed = true)
        publisher.onConnected("call1")

        verifyNothingWritten()
    }

    @Test
    fun `a new call starts with the camera off and the microphone on`() {
        writing("call1")
        publisher.update { it.copy(camera = true, mic = false) }

        writing("call2")

        coVerify(exactly = 1) { repository.setMedia("call2", camera = false, mic = true) }
    }
}
