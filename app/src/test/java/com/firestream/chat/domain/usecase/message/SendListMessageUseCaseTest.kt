package com.firestream.chat.domain.usecase.message

import com.firestream.chat.domain.model.Message
import com.firestream.chat.domain.model.MessageType
import com.firestream.chat.domain.repository.MessageRepository
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class SendListMessageUseCaseTest {

    private lateinit var messageRepository: MessageRepository
    private lateinit var useCase: SendListMessageUseCase

    @Before
    fun setUp() {
        messageRepository = mockk()
        useCase = SendListMessageUseCase(messageRepository)
    }

    @Test
    fun `invoke sends list message successfully`() = runTest {
        val message = Message(id = "msg1", type = MessageType.LIST, listId = "list1")
        coEvery { messageRepository.sendListMessage("chat1", "list1", "Groceries") } returns Result.success(message)

        val result = useCase("chat1", "list1", "Groceries")

        assertTrue(result.isSuccess)
        coVerify(exactly = 1) { messageRepository.sendListMessage("chat1", "list1", "Groceries") }
    }

    @Test
    fun `invoke returns failure when repository fails`() = runTest {
        coEvery { messageRepository.sendListMessage(any(), any(), any()) } returns Result.failure(Exception("Error"))

        val result = useCase("chat1", "list1", "Test")

        assertTrue(result.isFailure)
    }
}
