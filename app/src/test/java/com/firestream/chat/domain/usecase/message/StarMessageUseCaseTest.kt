package com.firestream.chat.domain.usecase.message

import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import com.firestream.chat.domain.repository.MessageRepository
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class StarMessageUseCaseTest {

    private lateinit var messageRepository: MessageRepository
    private lateinit var useCase: StarMessageUseCase

    @Before
    fun setUp() {
        messageRepository = mockk()
        useCase = StarMessageUseCase(messageRepository)
    }

    @Test
    fun `invoke stars message successfully`() = runTest {
        coEvery { messageRepository.starMessage("msg1", true) } returns Result.success(Unit)

        val result = useCase("msg1", true)

        assertTrue(result.isSuccess)
        coVerify(exactly = 1) { messageRepository.starMessage("msg1", true) }
    }

    @Test
    fun `invoke unstars message successfully`() = runTest {
        coEvery { messageRepository.starMessage("msg1", false) } returns Result.success(Unit)

        val result = useCase("msg1", false)

        assertTrue(result.isSuccess)
        coVerify(exactly = 1) { messageRepository.starMessage("msg1", false) }
    }

    @Test
    fun `invoke returns failure when repository fails`() = runTest {
        coEvery { messageRepository.starMessage(any(), any()) } returns Result.failure(Exception("DB error"))

        val result = useCase("msg1", true)

        assertTrue(result.isFailure)
    }
}
