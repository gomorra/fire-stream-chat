package com.firestream.chat.domain.usecase.chat

import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import com.firestream.chat.domain.repository.ChatRepository
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class ApproveMemberUseCaseTest {

    private lateinit var chatRepository: ChatRepository
    private lateinit var useCase: ApproveMemberUseCase

    @Before
    fun setUp() {
        chatRepository = mockk()
        useCase = ApproveMemberUseCase(chatRepository)
    }

    @Test
    fun `invoke approves member successfully`() = runTest {
        coEvery { chatRepository.approveMember("chat1", "user1") } returns Result.success(Unit)

        val result = useCase("chat1", "user1")

        assertTrue(result.isSuccess)
        coVerify(exactly = 1) { chatRepository.approveMember("chat1", "user1") }
    }

    @Test
    fun `invoke returns failure on repository error`() = runTest {
        coEvery { chatRepository.approveMember(any(), any()) } returns Result.failure(Exception("Permission denied"))

        val result = useCase("chat1", "user1")

        assertTrue(result.isFailure)
    }
}
