package com.firestream.chat.domain.usecase.chat

import com.firestream.chat.domain.model.Chat
import com.firestream.chat.domain.model.ChatType
import com.firestream.chat.domain.repository.ChatRepository
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class CreateBroadcastListUseCaseTest {

    private lateinit var chatRepository: ChatRepository
    private lateinit var useCase: CreateBroadcastListUseCase

    @Before
    fun setUp() {
        chatRepository = mockk()
        useCase = CreateBroadcastListUseCase(chatRepository)
    }

    @Test
    fun `invoke creates broadcast list successfully`() = runTest {
        val chat = Chat(
            id = "broadcast1",
            type = ChatType.BROADCAST,
            name = "My Broadcast",
            participants = listOf("creator", "user1", "user2")
        )
        coEvery { chatRepository.createBroadcastList("My Broadcast", listOf("user1", "user2")) } returns Result.success(chat)

        val result = useCase("My Broadcast", listOf("user1", "user2"))

        assertTrue(result.isSuccess)
        assertEquals(ChatType.BROADCAST, result.getOrNull()?.type)
        assertEquals("My Broadcast", result.getOrNull()?.name)
        coVerify(exactly = 1) { chatRepository.createBroadcastList("My Broadcast", listOf("user1", "user2")) }
    }

    @Test
    fun `invoke returns failure on repository error`() = runTest {
        coEvery { chatRepository.createBroadcastList(any(), any()) } returns Result.failure(Exception("Network error"))

        val result = useCase("Broadcast", listOf("user1"))

        assertTrue(result.isFailure)
    }

    @Test
    fun `invoke passes recipients to repository`() = runTest {
        val recipientIds = listOf("a", "b", "c")
        val chat = Chat(id = "b1", type = ChatType.BROADCAST, participants = recipientIds + "creator")
        coEvery { chatRepository.createBroadcastList("List", recipientIds) } returns Result.success(chat)

        val result = useCase("List", recipientIds)

        assertTrue(result.isSuccess)
        coVerify(exactly = 1) { chatRepository.createBroadcastList("List", recipientIds) }
    }
}
