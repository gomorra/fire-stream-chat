package com.firestream.chat.domain.usecase.chat

import com.firestream.chat.domain.repository.ChatRepository
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class DemoteFromAdminUseCaseTest {

    private lateinit var chatRepository: ChatRepository
    private lateinit var useCase: DemoteFromAdminUseCase

    @Before
    fun setUp() {
        chatRepository = mockk()
        useCase = DemoteFromAdminUseCase(chatRepository)
    }

    @Test
    fun `invoke demotes user from admin successfully`() = runTest {
        coEvery { chatRepository.demoteFromAdmin("chat1", "user1") } returns Result.success(Unit)

        val result = useCase("chat1", "user1")

        assertTrue(result.isSuccess)
        coVerify(exactly = 1) { chatRepository.demoteFromAdmin("chat1", "user1") }
    }

    @Test
    fun `invoke returns failure on repository error`() = runTest {
        coEvery { chatRepository.demoteFromAdmin(any(), any()) } returns Result.failure(Exception("Error"))

        val result = useCase("chat1", "user1")

        assertTrue(result.isFailure)
    }
}
