package com.firestream.chat.data.call

import com.firestream.chat.domain.model.CallSignalingData
import com.firestream.chat.domain.model.IceCandidateData
import com.firestream.chat.domain.model.SdpData
import com.firestream.chat.domain.repository.CallRepository
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class OneToOneSignalingTest {

    private val repository: CallRepository = mockk(relaxed = true)
    private val offer = SdpData("offer-sdp", "offer")
    private val answer = SdpData("answer-sdp", "answer")
    private val candidate = IceCandidateData("0", 0, "candidate")

    private fun call(status: String, offer: SdpData? = null, answer: SdpData? = null) = CallSignalingData(
        callId = "call",
        callerId = "caller",
        calleeId = "callee",
        status = status,
        offer = offer,
        answer = answer,
        createdAt = 0L,
        endedAt = null,
        endReason = null
    )

    private fun caller() = OneToOneSignaling(repository, "call", isCaller = true)
    private fun callee() = OneToOneSignaling(repository, "call", isCaller = false)

    @Test
    fun `the caller observes the callee's candidates and writes its own`() = runTest {
        every { repository.observeIceCandidates("call", "calleeCandidates") } returns flowOf(listOf(candidate))

        assertEquals(listOf(candidate), caller().observeCandidates().first())
        caller().sendCandidate(candidate)

        coVerify { repository.sendIceCandidate("call", true, candidate) }
        verify(exactly = 0) { repository.observeIceCandidates("call", "callerCandidates") }
    }

    @Test
    fun `the callee observes the caller's candidates and writes its own`() = runTest {
        every { repository.observeIceCandidates("call", "callerCandidates") } returns flowOf(listOf(candidate))

        assertEquals(listOf(candidate), callee().observeCandidates().first())
        callee().sendCandidate(candidate)

        coVerify { repository.sendIceCandidate("call", false, candidate) }
        verify(exactly = 0) { repository.observeIceCandidates("call", "calleeCandidates") }
    }

    @Test
    fun `the answer is written together with the answered status`() = runTest {
        callee().sendAnswer(answer)

        coVerify { repository.sendAnswerAndAccept("call", answer) }
        coVerify(exactly = 0) { repository.sendAnswer(any(), any()) }
    }

    @Test
    fun `the offer is written to the call document`() = runTest {
        caller().sendOffer(offer)

        coVerify { repository.sendOffer("call", offer) }
    }

    // CallSession reads the call document once before it answers, and CallSessionTest covers a
    // document that cannot be read. The signalling never fetches it a second time.
    @Test
    fun `the remote offer is the one the session read from the call document`() = runTest {
        val callee = OneToOneSignaling(repository, "call", isCaller = false, offer = offer)

        assertEquals(listOf(offer), callee.observeOffer().toList())
        coVerify(exactly = 0) { repository.getCallById(any()) }
    }

    @Test
    fun `a side without an offer fails the offer flow`() = runTest {
        assertTrue(runCatching { callee().observeOffer().first() }.isFailure)
    }

    @Test
    fun `the answer is emitted once the call is answered, and once per answer`() = runTest {
        every { repository.observeCallDocument("call") } returns flowOf(
            call("ringing", offer = offer),
            call("answered", offer = offer),
            call("answered", offer = offer, answer = answer),
            call("answered", offer = offer, answer = answer)
        )

        assertEquals(listOf(answer), caller().observeAnswer().toList())
    }
}
