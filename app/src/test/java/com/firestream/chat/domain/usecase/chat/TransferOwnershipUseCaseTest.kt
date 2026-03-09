package com.firestream.chat.domain.usecase.chat

import com.firestream.chat.domain.repository.ChatRepository
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class TransferOwnershipUseCaseTest {

    private lateinit var chatRepository: ChatRepository
    private lateinit var useCase: TransferOwnershipUseCase

    @Before
    fun setUp() {
        chatRepository = mockk()
        useCase = TransferOwnershipUseCase(chatRepository)
    }

    @Test
    fun `invoke transfers ownership successfully`() = runTest {
        coEvery { chatRepository.transferOwnership("chat1", "newOwner") } returns Result.success(Unit)

        val result = useCase("chat1", "newOwner")

        assertTrue(result.isSuccess)
        coVerify(exactly = 1) { chatRepository.transferOwnership("chat1", "newOwner") }
    }

    @Test
    fun `invoke returns failure on repository error`() = runTest {
        coEvery { chatRepository.transferOwnership(any(), any()) } returns Result.failure(Exception("Error"))

        val result = useCase("chat1", "newOwner")

        assertTrue(result.isFailure)
    }
}
