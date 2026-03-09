package com.firestream.chat.domain.usecase.message

import com.firestream.chat.domain.model.Message
import com.firestream.chat.domain.model.MessageType
import com.firestream.chat.domain.repository.MessageRepository
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class SendBroadcastMessageUseCaseTest {

    private lateinit var messageRepository: MessageRepository
    private lateinit var useCase: SendBroadcastMessageUseCase

    @Before
    fun setUp() {
        messageRepository = mockk()
        useCase = SendBroadcastMessageUseCase(messageRepository)
    }

    @Test
    fun `invoke sends broadcast message successfully`() = runTest {
        val message = Message(
            id = "msg1",
            chatId = "broadcast1",
            senderId = "creator",
            content = "Hello everyone!",
            type = MessageType.TEXT
        )
        val recipientIds = listOf("user1", "user2", "user3")
        coEvery {
            messageRepository.sendBroadcastMessage("broadcast1", "Hello everyone!", recipientIds)
        } returns Result.success(message)

        val result = useCase("broadcast1", "Hello everyone!", recipientIds)

        assertTrue(result.isSuccess)
        assertEquals("msg1", result.getOrNull()?.id)
        assertEquals(MessageType.TEXT, result.getOrNull()?.type)
        coVerify(exactly = 1) {
            messageRepository.sendBroadcastMessage("broadcast1", "Hello everyone!", recipientIds)
        }
    }

    @Test
    fun `invoke returns failure when repository fails`() = runTest {
        coEvery {
            messageRepository.sendBroadcastMessage(any(), any(), any())
        } returns Result.failure(Exception("Send failed"))

        val result = useCase("broadcast1", "Hi", listOf("user1"))

        assertTrue(result.isFailure)
    }

    @Test
    fun `invoke passes all recipients to repository`() = runTest {
        val recipientIds = listOf("a", "b", "c", "d", "e")
        val message = Message(id = "m1", chatId = "b1", senderId = "me", content = "Msg")
        coEvery { messageRepository.sendBroadcastMessage("b1", "Msg", recipientIds) } returns Result.success(message)

        val result = useCase("b1", "Msg", recipientIds)

        assertTrue(result.isSuccess)
        coVerify(exactly = 1) { messageRepository.sendBroadcastMessage("b1", "Msg", recipientIds) }
    }
}
