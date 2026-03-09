package com.firestream.chat.domain.usecase.chat

import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import com.firestream.chat.domain.model.Chat
import com.firestream.chat.domain.model.ChatType
import com.firestream.chat.domain.repository.ChatRepository
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class JoinGroupViaLinkUseCaseTest {

    private lateinit var chatRepository: ChatRepository
    private lateinit var useCase: JoinGroupViaLinkUseCase

    @Before
    fun setUp() {
        chatRepository = mockk()
        useCase = JoinGroupViaLinkUseCase(chatRepository)
    }

    @Test
    fun `invoke joins group successfully`() = runTest {
        val chat = Chat(id = "chat1", type = ChatType.GROUP, name = "Test Group")
        coEvery { chatRepository.joinGroupViaLink("token-123") } returns Result.success(chat)

        val result = useCase("token-123")

        assertTrue(result.isSuccess)
        assertEquals("chat1", result.getOrNull()?.id)
        coVerify(exactly = 1) { chatRepository.joinGroupViaLink("token-123") }
    }

    @Test
    fun `invoke returns failure for invalid token`() = runTest {
        coEvery { chatRepository.joinGroupViaLink(any()) } returns Result.failure(Exception("Invalid or expired invite link"))

        val result = useCase("bad-token")

        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull()?.message?.contains("Invalid") == true)
    }

    @Test
    fun `invoke returns failure when already a member`() = runTest {
        coEvery { chatRepository.joinGroupViaLink(any()) } returns Result.failure(Exception("Already a member of this group"))

        val result = useCase("token-123")

        assertTrue(result.isFailure)
    }
}
