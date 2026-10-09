package com.firestream.chat.data.repository

import com.firestream.chat.data.call.IceServerProvider
import com.firestream.chat.data.outbox.SendClock
import com.firestream.chat.data.remote.source.AuthSource
import com.firestream.chat.data.remote.source.CallSignalingSource
import com.firestream.chat.data.remote.source.MessageSource
import com.firestream.chat.domain.model.EndReason
import com.firestream.chat.domain.model.IceCandidateData
import com.firestream.chat.domain.model.OutgoingCall
import com.firestream.chat.domain.model.SdpData
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.coVerifyOrder
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.IOException

/**
 * Placing a call decides whether its offer may carry a video line. An app without video crashes on
 * an offer that has one, so every doubt about the callee's app means no video line. The call is
 * placed either way.
 *
 * Every call lets cancellation through, and writes an end as its wire name.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class CallRepositoryImplTest {

    private val callSource: CallSignalingSource = mockk(relaxed = true)
    private val authSource: AuthSource = mockk(relaxed = true)
    private val messageSource: MessageSource = mockk(relaxed = true)
    private val iceServerProvider: IceServerProvider = mockk(relaxed = true)

    private val repository = CallRepositoryImpl(
        callSource = callSource,
        authSource = authSource,
        messageSource = messageSource,
        chatDao = mockk(relaxed = true),
        sendClock = SendClock(),
        iceServerProvider = iceServerProvider,
    )

    private val sdp = SdpData("v=0", "offer")
    private val candidate = IceCandidateData("audio", 0, "candidate:1")

    @Before
    fun setUp() {
        coEvery { iceServerProvider.get() } returns IceServerProvider.STUN_ONLY
        every { authSource.currentUserId } returns "caller1"
        coEvery { callSource.createCallDocument("caller1", "callee1", any()) } returns "call1"
    }

    /**
     * Run [call] in a coroutine that is cancelled while [call] is suspended in the source, and
     * report whether the coroutine went on past it.
     */
    private fun TestScope.resumesAfterCancellation(call: suspend () -> Unit): Boolean {
        var resumed = false
        val job = launch {
            call()
            resumed = true
        }
        runCurrent()
        job.cancel()
        runCurrent()
        assertTrue(job.isCancelled)
        return resumed
    }

    @Test
    fun `a caller cancelled in any repository call stops instead of getting a failure`() = runTest {
        coEvery { callSource.createCallDocument(any(), any(), any()) } coAnswers { awaitCancellation() }
        coEvery { callSource.updateCallStatus(any(), any(), any()) } coAnswers { awaitCancellation() }
        coEvery { callSource.setOffer(any(), any()) } coAnswers { awaitCancellation() }
        coEvery { callSource.setAnswer(any(), any()) } coAnswers { awaitCancellation() }
        coEvery { callSource.setAnswerAndAccept(any(), any()) } coAnswers { awaitCancellation() }
        coEvery { callSource.addIceCandidate(any(), any(), any()) } coAnswers { awaitCancellation() }
        coEvery { callSource.setMedia(any(), any(), any(), any()) } coAnswers { awaitCancellation() }
        coEvery { callSource.getCallById(any()) } coAnswers { awaitCancellation() }
        coEvery { messageSource.sendCallMessage(any(), any(), any(), any(), any(), any()) } coAnswers { awaitCancellation() }

        val calls: Map<String, suspend () -> Unit> = mapOf(
            "createCall" to { repository.createCall("callee", video = false) },
            "answerCall" to { repository.answerCall("c1") },
            "declineCall" to { repository.declineCall("c1") },
            "endCall" to { repository.endCall("c1", EndReason.HANGUP) },
            "sendOffer" to { repository.sendOffer("c1", sdp) },
            "sendAnswer" to { repository.sendAnswer("c1", sdp) },
            "sendAnswerAndAccept" to { repository.sendAnswerAndAccept("c1", sdp) },
            "sendIceCandidate" to { repository.sendIceCandidate("c1", isCaller = true, candidate) },
            "setMedia" to { repository.setMedia("c1", camera = true, mic = true) },
            "getCallById" to { repository.getCallById("c1") },
            "logCallMessage" to { repository.logCallMessage("chat1", EndReason.HANGUP, 0, video = false) },
        )

        val resumed = calls.filter { (_, call) -> resumesAfterCancellation(call) }.keys
        assertEquals(emptySet<String>(), resumed)
    }

    @Test
    fun `an end is written as its wire name, which the push function reads`() = runTest {
        // functions/callPush.js tells a declined call from a missed one by these exact strings.
        coEvery { messageSource.sendCallMessage(any(), any(), any(), any(), any(), any()) } returns "m1"
        every { messageSource.lastContentFor(any()) } returns "Call"

        repository.endCall("c1", EndReason.REMOTE_HANGUP)
        repository.declineCall("c2")
        repository.logCallMessage("chat1", EndReason.TIMEOUT, 0, video = true)

        coVerify { callSource.updateCallStatus("c1", "ended", "remote_hangup") }
        coVerify { callSource.updateCallStatus("c2", "declined", "declined") }
        // The call's kind travels beside the typed end reason.
        coVerify { messageSource.sendCallMessage("chat1", "caller1", "timeout", 0, true, any()) }
    }

    @Test
    fun `a failing source still comes back as a failure`() = runTest {
        coEvery { callSource.updateCallStatus(any(), any(), any()) } throws IllegalStateException("offline")

        val result = repository.endCall("c1", EndReason.HANGUP)

        assertTrue(result.isFailure)
        assertFalse(result.exceptionOrNull() is kotlinx.coroutines.CancellationException)
    }

    @Test
    fun `a callee whose app takes video is offered a video line`() = runTest {
        coEvery { authSource.takesCallVideoLine("callee1") } returns true

        val call = repository.createCall("callee1", video = false).getOrThrow()

        assertEquals(OutgoingCall("call1", videoLine = true), call)
    }

    // The source reads a user document without the field as false: an app from before video.
    @Test
    fun `a callee whose user document lacks the field is offered no video line`() = runTest {
        coEvery { authSource.takesCallVideoLine("callee1") } returns false

        val call = repository.createCall("callee1", video = true).getOrThrow()

        // Started as video all the same. The call runs as a voice call.
        assertEquals(OutgoingCall("call1", videoLine = false), call)
        coVerify { callSource.createCallDocument("caller1", "callee1", true) }
    }

    @Test
    fun `a read that fails offers no video line, and the call is still placed`() = runTest {
        coEvery { authSource.takesCallVideoLine("callee1") } throws IOException("unavailable")

        val call = repository.createCall("callee1", video = false).getOrThrow()

        assertEquals(OutgoingCall("call1", videoLine = false), call)
    }

    @Test
    fun `a read that takes too long offers no video line, and the call is still placed`() = runTest {
        coEvery { authSource.takesCallVideoLine("callee1") } coAnswers {
            delay(60_000)
            true
        }

        val call = repository.createCall("callee1", video = false).getOrThrow()

        assertEquals(OutgoingCall("call1", videoLine = false), call)
        // The ring waited three seconds for the answer, and no longer.
        assertEquals(3_000, currentTime)
    }

    // Creating the document rings the callee, who answers by fetching the offer once. Nothing may
    // wait between the document and the offer, so the read comes first.
    @Test
    fun `the callee's app is asked before the call document exists`() = runTest {
        coEvery { authSource.takesCallVideoLine("callee1") } returns true

        repository.createCall("callee1", video = false)

        coVerifyOrder {
            authSource.takesCallVideoLine("callee1")
            callSource.createCallDocument("caller1", "callee1", false)
        }
    }

    // The call service builds the offer the moment the call exists, from what the provider keeps.
    @Test
    fun `the relay's servers are fetched before the call document exists`() = runTest {
        coEvery { iceServerProvider.get() } coAnswers {
            delay(2_000)
            IceServerProvider.STUN_ONLY
        }

        repository.createCall("callee1", video = false)

        assertEquals(2_000, currentTime)
        coVerifyOrder {
            iceServerProvider.get()
            callSource.createCallDocument("caller1", "callee1", false)
        }
    }

    @Test
    fun `the two waits before the ring run side by side`() = runTest {
        coEvery { authSource.takesCallVideoLine("callee1") } coAnswers {
            delay(2_500)
            true
        }
        coEvery { iceServerProvider.get() } coAnswers {
            delay(3_000)
            IceServerProvider.STUN_ONLY
        }

        val call = repository.createCall("callee1", video = true).getOrThrow()

        assertEquals(OutgoingCall("call1", videoLine = true), call)
        assertEquals(3_000, currentTime)
    }

    @Test
    fun `preparing a call starts the fetch and does not wait for it`() {
        repository.prepareCall()

        verify { iceServerProvider.warm() }
    }

    @Test
    fun `signed out, nobody is asked and nothing rings`() = runTest {
        every { authSource.currentUserId } returns null

        val result = repository.createCall("callee1", video = false)

        assertTrue(result.isFailure)
        coVerify(exactly = 0) { authSource.takesCallVideoLine(any()) }
        coVerify(exactly = 0) { iceServerProvider.get() }
        coVerify(exactly = 0) { callSource.createCallDocument(any(), any(), any()) }
    }
}
