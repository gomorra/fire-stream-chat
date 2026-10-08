package com.firestream.chat.data.repository

import com.firestream.chat.data.local.dao.ChatDao
import com.firestream.chat.data.outbox.SendClock
import com.firestream.chat.data.remote.source.AuthSource
import com.firestream.chat.data.remote.source.CallSignalingSource
import com.firestream.chat.data.remote.source.MessageSource
import com.firestream.chat.domain.model.IceCandidateData
import com.firestream.chat.domain.model.SdpData
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class CallRepositoryImplTest {

    private val callSource = mockk<CallSignalingSource>()
    private val authSource = mockk<AuthSource> { every { currentUserId } returns "me" }
    private val messageSource = mockk<MessageSource>()
    private val chatDao = mockk<ChatDao>(relaxed = true)

    private val repository = CallRepositoryImpl(callSource, authSource, messageSource, chatDao, SendClock())

    private val sdp = SdpData("v=0", "offer")
    private val candidate = IceCandidateData("audio", 0, "candidate:1")

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
        coEvery { callSource.createCallDocument(any(), any()) } coAnswers { awaitCancellation() }
        coEvery { callSource.updateCallStatus(any(), any(), any()) } coAnswers { awaitCancellation() }
        coEvery { callSource.setOffer(any(), any()) } coAnswers { awaitCancellation() }
        coEvery { callSource.setAnswer(any(), any()) } coAnswers { awaitCancellation() }
        coEvery { callSource.setAnswerAndAccept(any(), any()) } coAnswers { awaitCancellation() }
        coEvery { callSource.addIceCandidate(any(), any(), any()) } coAnswers { awaitCancellation() }
        coEvery { callSource.getCallById(any()) } coAnswers { awaitCancellation() }
        coEvery { messageSource.sendCallMessage(any(), any(), any(), any(), any()) } coAnswers { awaitCancellation() }

        val calls: Map<String, suspend () -> Unit> = mapOf(
            "createCall" to { repository.createCall("callee") },
            "answerCall" to { repository.answerCall("c1") },
            "declineCall" to { repository.declineCall("c1") },
            "endCall" to { repository.endCall("c1", "hangup") },
            "sendOffer" to { repository.sendOffer("c1", sdp) },
            "sendAnswer" to { repository.sendAnswer("c1", sdp) },
            "sendAnswerAndAccept" to { repository.sendAnswerAndAccept("c1", sdp) },
            "sendIceCandidate" to { repository.sendIceCandidate("c1", isCaller = true, candidate) },
            "getCallById" to { repository.getCallById("c1") },
            "logCallMessage" to { repository.logCallMessage("chat1", "hangup", 0) },
        )

        val resumed = calls.filter { (_, call) -> resumesAfterCancellation(call) }.keys
        assertEquals(emptySet<String>(), resumed)
    }

    @Test
    fun `a failing source still comes back as a failure`() = runTest {
        coEvery { callSource.updateCallStatus(any(), any(), any()) } throws IllegalStateException("offline")

        val result = repository.endCall("c1", "hangup")

        assertTrue(result.isFailure)
        assertFalse(result.exceptionOrNull() is kotlinx.coroutines.CancellationException)
    }
}
