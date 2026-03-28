package com.firestream.chat.domain.usecase.message

import com.firestream.chat.domain.model.Message
import com.firestream.chat.domain.model.MessageType
import com.firestream.chat.domain.model.Poll
import com.firestream.chat.domain.model.PollOption
import com.firestream.chat.domain.repository.PollRepository
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class SendPollUseCaseTest {

    private lateinit var pollRepository: PollRepository
    private lateinit var useCase: SendPollUseCase

    @Before
    fun setUp() {
        pollRepository = mockk()
        useCase = SendPollUseCase(pollRepository)
    }

    @Test
    fun `invoke sends poll successfully`() = runTest {
        val options = listOf("Yes", "No", "Maybe")
        val pollData = Poll(
            question = "Is this working?",
            options = options.mapIndexed { i, text -> PollOption(id = "opt_$i", text = text) },
            isMultipleChoice = false,
            isAnonymous = false
        )
        val message = Message(
            id = "msg1",
            chatId = "chat1",
            senderId = "user1",
            content = "📊 Poll",
            type = MessageType.POLL,
            pollData = pollData
        )

        coEvery {
            pollRepository.sendPoll("chat1", "Is this working?", options, false, false)
        } returns Result.success(message)

        val result = useCase("chat1", "Is this working?", options, false, false)

        assertTrue(result.isSuccess)
        assertEquals(MessageType.POLL, result.getOrNull()?.type)
        assertEquals("Is this working?", result.getOrNull()?.pollData?.question)
        coVerify(exactly = 1) {
            pollRepository.sendPoll("chat1", "Is this working?", options, false, false)
        }
    }

    @Test
    fun `invoke returns failure when repository fails`() = runTest {
        coEvery {
            pollRepository.sendPoll(any(), any(), any(), any(), any())
        } returns Result.failure(Exception("Network error"))

        val result = useCase("chat1", "Question?", listOf("A", "B"), false, false)

        assertTrue(result.isFailure)
    }

    @Test
    fun `invoke sends multiple choice poll`() = runTest {
        val options = listOf("A", "B", "C")
        val message = Message(
            id = "msg2",
            chatId = "chat1",
            type = MessageType.POLL,
            pollData = Poll(
                question = "Pick multiple",
                options = options.mapIndexed { i, text -> PollOption(id = "opt_$i", text = text) },
                isMultipleChoice = true,
                isAnonymous = true
            )
        )

        coEvery {
            pollRepository.sendPoll("chat1", "Pick multiple", options, true, true)
        } returns Result.success(message)

        val result = useCase("chat1", "Pick multiple", options, true, true)

        assertTrue(result.isSuccess)
        assertTrue(result.getOrNull()?.pollData?.isMultipleChoice == true)
        assertTrue(result.getOrNull()?.pollData?.isAnonymous == true)
    }
}
