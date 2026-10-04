package com.firestream.chat.data.repository

import com.firestream.chat.data.remote.source.AuthSource
import com.firestream.chat.data.remote.source.CallSignalingSource
import com.firestream.chat.domain.model.OutgoingCall
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.coVerifyOrder
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.IOException

/**
 * Placing a call decides whether its offer may carry a video line. An app without video crashes on
 * an offer that has one, so every doubt about the callee's app means no video line. The call is
 * placed either way.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class CallRepositoryImplTest {

    private val callSource: CallSignalingSource = mockk(relaxed = true)
    private val authSource: AuthSource = mockk(relaxed = true)

    private val repository = CallRepositoryImpl(
        callSource = callSource,
        authSource = authSource,
        messageSource = mockk(relaxed = true),
        chatDao = mockk(relaxed = true),
        sendClock = mockk(relaxed = true),
    )

    @Before
    fun setUp() {
        every { authSource.currentUserId } returns "caller1"
        coEvery { callSource.createCallDocument("caller1", "callee1", any()) } returns "call1"
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

    @Test
    fun `signed out, nobody is asked and nothing rings`() = runTest {
        every { authSource.currentUserId } returns null

        val result = repository.createCall("callee1", video = false)

        assertTrue(result.isFailure)
        coVerify(exactly = 0) { authSource.takesCallVideoLine(any()) }
        coVerify(exactly = 0) { callSource.createCallDocument(any(), any(), any()) }
    }
}
