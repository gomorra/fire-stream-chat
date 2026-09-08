package com.firestream.chat.domain.usecase.message

import com.firestream.chat.domain.model.Message
import com.firestream.chat.domain.model.MessageFilterType
import com.firestream.chat.domain.model.MessageSearchFilter
import com.firestream.chat.domain.model.MessageSearchResults
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

    private val samplePhoto = Message(
        id = "m2",
        chatId = "c1",
        senderId = "u1",
        content = "",
        type = MessageType.IMAGE,
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
        assertTrue(result.messages.isEmpty())
    }

    @Test
    fun `returns empty list for whitespace-only query`() = runTest {
        val result = useCase("   ")
        assertTrue(result.messages.isEmpty())
    }

    @Test
    fun `performs global search when no chatId provided`() = runTest {
        coEvery { messageRepository.searchMessages(null, "hello", MessageSearchFilter.NONE) } returns MessageSearchResults(listOf(sampleMessage))

        val result = useCase("hello")

        assertEquals(1, result.messages.size)
        assertEquals(sampleMessage, result.messages.first())
        coVerify(exactly = 1) { messageRepository.searchMessages(null, "hello", MessageSearchFilter.NONE) }
    }

    @Test
    fun `performs in-chat search when chatId provided`() = runTest {
        coEvery {
            messageRepository.searchMessages("c1", "world", MessageSearchFilter.NONE)
        } returns MessageSearchResults(listOf(sampleMessage))

        val result = useCase("world", chatId = "c1")

        assertEquals(1, result.messages.size)
        coVerify(exactly = 1) {
            messageRepository.searchMessages("c1", "world", MessageSearchFilter.NONE)
        }
    }

    @Test
    fun `returns empty list when no results found`() = runTest {
        coEvery { messageRepository.searchMessages(null, "notfound", MessageSearchFilter.NONE) } returns MessageSearchResults.EMPTY

        val result = useCase("notfound")

        assertTrue(result.messages.isEmpty())
    }

    // ── Browse mode: blank query + active filter ─────────────────────────────

    @Test
    fun `blank query with an active filter browses instead of returning empty`() = runTest {
        val filter = MessageSearchFilter(type = MessageFilterType.PHOTOS)
        coEvery { messageRepository.searchMessages("c1", "", filter) } returns MessageSearchResults(listOf(samplePhoto))

        val result = useCase("", chatId = "c1", filter = filter)

        assertEquals(listOf(samplePhoto), result.messages)
        coVerify(exactly = 1) { messageRepository.searchMessages("c1", "", filter) }
    }

    @Test
    fun `whitespace-only query with an active filter reaches the repository as empty string`() = runTest {
        val filter = MessageSearchFilter(isStarred = true)
        coEvery { messageRepository.searchMessages("c1", "", filter) } returns MessageSearchResults(listOf(sampleMessage))

        val result = useCase("   ", chatId = "c1", filter = filter)

        assertEquals(1, result.messages.size)
        coVerify(exactly = 1) { messageRepository.searchMessages("c1", "", filter) }
    }

    @Test
    fun `blank query with no active filter still returns empty in-chat`() = runTest {
        val result = useCase("", chatId = "c1", filter = MessageSearchFilter.NONE)

        assertTrue(result.messages.isEmpty())
        coVerify(exactly = 0) { messageRepository.searchMessages(any(), any(), any()) }
    }

    @Test
    fun `a date range alone counts as an active filter`() = runTest {
        val filter = MessageSearchFilter(fromMs = 1_000L, toMs = 2_000L)
        coEvery { messageRepository.searchMessages("c1", "", filter) } returns MessageSearchResults(listOf(sampleMessage))

        val result = useCase("", chatId = "c1", filter = filter)

        assertEquals(1, result.messages.size)
    }

    @Test
    fun `filter reaches the repository unchanged alongside a text query`() = runTest {
        val filter = MessageSearchFilter(
            type = MessageFilterType.DOCS,
            isStarred = true,
            fromMs = 10L,
            toMs = 99L,
        )
        coEvery { messageRepository.searchMessages("c1", "report", filter) } returns MessageSearchResults.EMPTY

        useCase("report", chatId = "c1", filter = filter)

        coVerify(exactly = 1) { messageRepository.searchMessages("c1", "report", filter) }
    }

    @Test
    fun `global search forwards its filter like an in-chat search does`() = runTest {
        val filter = MessageSearchFilter(isStarred = true)
        coEvery { messageRepository.searchMessages(null, "hello", filter) } returns
            MessageSearchResults(listOf(sampleMessage))

        val result = useCase("hello", chatId = null, filter = filter)

        assertEquals(1, result.messages.size)
        coVerify(exactly = 1) { messageRepository.searchMessages(null, "hello", filter) }
    }

    @Test
    fun `global browse mode - a blank query with an active filter still queries`() = runTest {
        val filter = MessageSearchFilter(type = MessageFilterType.PHOTOS)
        coEvery { messageRepository.searchMessages(null, "", filter) } returns
            MessageSearchResults(listOf(samplePhoto))

        val result = useCase("", chatId = null, filter = filter)

        assertEquals(listOf(samplePhoto), result.messages)
        coVerify(exactly = 1) { messageRepository.searchMessages(null, "", filter) }
    }

    @Test
    fun `a blank global query with no filter never reaches the repository`() = runTest {
        val result = useCase("   ", chatId = null, filter = MessageSearchFilter.NONE)

        assertTrue(result.messages.isEmpty())
        coVerify(exactly = 0) { messageRepository.searchMessages(any(), any(), any()) }
    }
}
