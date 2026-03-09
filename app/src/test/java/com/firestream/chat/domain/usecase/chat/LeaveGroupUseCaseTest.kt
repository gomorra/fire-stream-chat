package com.firestream.chat.domain.usecase.chat

import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import com.firestream.chat.domain.repository.ChatRepository
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class LeaveGroupUseCaseTest {

    private lateinit var chatRepository: ChatRepository
    private lateinit var useCase: LeaveGroupUseCase

    @Before
    fun setUp() {
        chatRepository = mockk()
        useCase = LeaveGroupUseCase(chatRepository)
    }

    @Test
    fun `invoke leaves group successfully`() = runTest {
        coEvery { chatRepository.leaveGroup("chat1") } returns Result.success(Unit)

        val result = useCase("chat1")

        assertTrue(result.isSuccess)
        coVerify(exactly = 1) { chatRepository.leaveGroup("chat1") }
    }

    @Test
    fun `invoke returns failure when chat not found`() = runTest {
        coEvery { chatRepository.leaveGroup(any()) } returns Result.failure(Exception("Chat not found"))

        val result = useCase("chat1")

        assertTrue(result.isFailure)
    }

    @Test
    fun `invoke returns failure on auth error`() = runTest {
        coEvery { chatRepository.leaveGroup(any()) } returns Result.failure(Exception("Not authenticated"))

        val result = useCase("chat1")

        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull()?.message?.contains("Not authenticated") == true)
    }
}
