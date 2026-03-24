package com.firestream.chat.domain.usecase.list

import com.firestream.chat.domain.model.ListDiff
import com.firestream.chat.domain.model.Message
import com.firestream.chat.domain.model.MessageType
import com.firestream.chat.domain.repository.MessageRepository
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Test

class SendListUpdateToChatsUseCaseTest {

    private lateinit var messageRepository: MessageRepository
    private lateinit var useCase: SendListUpdateToChatsUseCase

    @Before
    fun setUp() {
        messageRepository = mockk()
    }

    private fun fakeMessage() = Message(type = MessageType.LIST)

    @Test
    fun `sends update to all shared chats`() = runTest {
        useCase = SendListUpdateToChatsUseCase(messageRepository)
        val diff = ListDiff(added = listOf("Milk"), removed = listOf("Eggs"))
        coEvery { messageRepository.sendListMessage(any(), any(), any(), any()) } returns
            Result.success(fakeMessage())

        useCase("list1", "Groceries", listOf("chat1", "chat2"), diff)

        coVerify(exactly = 1) { messageRepository.sendListMessage("chat1", "list1", "Groceries", diff) }
        coVerify(exactly = 1) { messageRepository.sendListMessage("chat2", "list1", "Groceries", diff) }
    }

    @Test
    fun `continues sending to remaining chats when one fails`() = runTest {
        useCase = SendListUpdateToChatsUseCase(messageRepository)
        val diff = ListDiff(checked = listOf("Apples"))
        coEvery { messageRepository.sendListMessage("chat1", any(), any(), any()) } throws Exception("Network error")
        coEvery { messageRepository.sendListMessage("chat2", any(), any(), any()) } returns
            Result.success(fakeMessage())

        useCase("list1", "Groceries", listOf("chat1", "chat2"), diff)

        coVerify(exactly = 1) { messageRepository.sendListMessage("chat2", "list1", "Groceries", diff) }
    }

    @Test
    fun `does nothing when shared chat list is empty`() = runTest {
        useCase = SendListUpdateToChatsUseCase(messageRepository)
        val diff = ListDiff(added = listOf("Bread"))

        useCase("list1", "Groceries", emptyList(), diff)

        coVerify(exactly = 0) { messageRepository.sendListMessage(any(), any(), any(), any()) }
    }
}
