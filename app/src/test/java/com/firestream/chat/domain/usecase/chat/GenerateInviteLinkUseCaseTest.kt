package com.firestream.chat.domain.usecase.chat

import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import com.firestream.chat.domain.repository.ChatRepository
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class GenerateInviteLinkUseCaseTest {

    private lateinit var chatRepository: ChatRepository
    private lateinit var useCase: GenerateInviteLinkUseCase

    @Before
    fun setUp() {
        chatRepository = mockk()
        useCase = GenerateInviteLinkUseCase(chatRepository)
    }

    @Test
    fun `invoke generates invite link successfully`() = runTest {
        val token = "abc-123-token"
        coEvery { chatRepository.generateInviteLink("chat1") } returns Result.success(token)

        val result = useCase("chat1")

        assertTrue(result.isSuccess)
        assertEquals(token, result.getOrNull())
        coVerify(exactly = 1) { chatRepository.generateInviteLink("chat1") }
    }

    @Test
    fun `invoke returns failure on repository error`() = runTest {
        coEvery { chatRepository.generateInviteLink(any()) } returns Result.failure(Exception("Not authenticated"))

        val result = useCase("chat1")

        assertTrue(result.isFailure)
    }
}
