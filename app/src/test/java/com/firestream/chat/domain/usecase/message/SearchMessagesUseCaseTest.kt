package com.firestream.chat.domain.usecase.message

import com.firestream.chat.domain.model.Message
import com.firestream.chat.domain.model.MessageStatus
import com.firestream.chat.domain.model.MessageType
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import com.firestream.chat.domain.repository.MessageRepository
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class SearchMessagesUseCaseTest {

    private lateinit var messageRepository: MessageRepository
    private lateinit var useCase: SearchMessagesUseCase

    private val sampleMessage = Message(
        id = "m1",
        chatId = "c1",
        senderId = "u1",
        content = "Hello world",
        type = MessageType.TEXT,
        status = MessageStatus.SENT
    )

    @Before
    fun setUp() {
        messageRepository = mockk()
        useCase = SearchMessagesUseCase(messageRepository)
    }

    @Test
    fun `returns empty list for blank query`() = runTest {
        val result = useCase("")
        assertTrue(result.isEmpty())
    }

    @Test
    fun `returns empty list for whitespace-only query`() = runTest {
        val result = useCase("   ")
        assertTrue(result.isEmpty())
    }

    @Test
    fun `performs global search when no chatId provided`() = runTest {
        coEvery { messageRepository.searchMessages("hello") } returns listOf(sampleMessage)

        val result = useCase("hello")

        assertEquals(1, result.size)
        assertEquals(sampleMessage, result.first())
        coVerify(exactly = 1) { messageRepository.searchMessages("hello") }
    }

    @Test
    fun `performs in-chat search when chatId provided`() = runTest {
        coEvery { messageRepository.searchMessagesInChat("c1", "world") } returns listOf(sampleMessage)

        val result = useCase("world", chatId = "c1")

        assertEquals(1, result.size)
        coVerify(exactly = 1) { messageRepository.searchMessagesInChat("c1", "world") }
    }

    @Test
    fun `returns empty list when no results found`() = runTest {
        coEvery { messageRepository.searchMessages("notfound") } returns emptyList()

        val result = useCase("notfound")

        assertTrue(result.isEmpty())
    }
}
