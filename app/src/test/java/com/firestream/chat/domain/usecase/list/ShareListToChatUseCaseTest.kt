package com.firestream.chat.domain.usecase.list

import com.firestream.chat.domain.model.Message
import com.firestream.chat.domain.model.MessageType
import com.firestream.chat.domain.repository.ListRepository
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class ShareListToChatUseCaseTest {

    private lateinit var listRepository: ListRepository
    private lateinit var useCase: ShareListToChatUseCase

    @Before
    fun setUp() {
        listRepository = mockk()
        useCase = ShareListToChatUseCase(listRepository)
    }

    @Test
    fun `invoke shares list to chat successfully`() = runTest {
        val message = Message(id = "msg1", type = MessageType.LIST, listId = "list1")
        coEvery { listRepository.shareListToChat("list1", "chat1") } returns Result.success(message)

        val result = useCase("list1", "chat1")

        assertTrue(result.isSuccess)
        assertTrue(result.getOrNull()?.listId == "list1")
        coVerify(exactly = 1) { listRepository.shareListToChat("list1", "chat1") }
    }

    @Test
    fun `invoke returns failure when repository fails`() = runTest {
        coEvery { listRepository.shareListToChat(any(), any()) } returns Result.failure(Exception("Error"))

        val result = useCase("list1", "chat1")

        assertTrue(result.isFailure)
    }
}
