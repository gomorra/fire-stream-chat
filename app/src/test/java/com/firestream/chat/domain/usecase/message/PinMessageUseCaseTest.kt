package com.firestream.chat.domain.usecase.message

import com.firestream.chat.domain.repository.MessageRepository
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class PinMessageUseCaseTest {

    private lateinit var messageRepository: MessageRepository
    private lateinit var useCase: PinMessageUseCase

    @Before
    fun setUp() {
        messageRepository = mockk()
        useCase = PinMessageUseCase(messageRepository)
    }

    @Test
    fun `invoke pins message successfully`() = runTest {
        coEvery { messageRepository.pinMessage("chat1", "msg1", true) } returns Result.success(Unit)

        val result = useCase("chat1", "msg1", true)

        assertTrue(result.isSuccess)
        coVerify(exactly = 1) { messageRepository.pinMessage("chat1", "msg1", true) }
    }

    @Test
    fun `invoke unpins message successfully`() = runTest {
        coEvery { messageRepository.pinMessage("chat1", "msg1", false) } returns Result.success(Unit)

        val result = useCase("chat1", "msg1", false)

        assertTrue(result.isSuccess)
        coVerify(exactly = 1) { messageRepository.pinMessage("chat1", "msg1", false) }
    }

    @Test
    fun `invoke returns failure when repository fails`() = runTest {
        coEvery { messageRepository.pinMessage(any(), any(), any()) } returns Result.failure(Exception("Error"))

        val result = useCase("chat1", "msg1", true)

        assertTrue(result.isFailure)
    }
}
