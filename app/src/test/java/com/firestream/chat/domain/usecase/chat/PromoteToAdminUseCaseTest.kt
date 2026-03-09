package com.firestream.chat.domain.usecase.chat

import com.firestream.chat.domain.repository.ChatRepository
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class PromoteToAdminUseCaseTest {

    private lateinit var chatRepository: ChatRepository
    private lateinit var useCase: PromoteToAdminUseCase

    @Before
    fun setUp() {
        chatRepository = mockk()
        useCase = PromoteToAdminUseCase(chatRepository)
    }

    @Test
    fun `invoke promotes user to admin successfully`() = runTest {
        coEvery { chatRepository.promoteToAdmin("chat1", "user1") } returns Result.success(Unit)

        val result = useCase("chat1", "user1")

        assertTrue(result.isSuccess)
        coVerify(exactly = 1) { chatRepository.promoteToAdmin("chat1", "user1") }
    }

    @Test
    fun `invoke returns failure on repository error`() = runTest {
        coEvery { chatRepository.promoteToAdmin(any(), any()) } returns Result.failure(Exception("Error"))

        val result = useCase("chat1", "user1")

        assertTrue(result.isFailure)
    }
}
