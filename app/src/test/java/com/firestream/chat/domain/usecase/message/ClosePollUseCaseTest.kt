package com.firestream.chat.domain.usecase.message

import com.firestream.chat.domain.repository.MessageRepository
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class ClosePollUseCaseTest {

    private lateinit var messageRepository: MessageRepository
    private lateinit var useCase: ClosePollUseCase

    @Before
    fun setUp() {
        messageRepository = mockk()
        useCase = ClosePollUseCase(messageRepository)
    }

    @Test
    fun `invoke closes poll successfully`() = runTest {
        coEvery { messageRepository.closePoll("chat1", "msg1") } returns Result.success(Unit)

        val result = useCase("chat1", "msg1")

        assertTrue(result.isSuccess)
        coVerify(exactly = 1) { messageRepository.closePoll("chat1", "msg1") }
    }

    @Test
    fun `invoke returns failure when repository fails`() = runTest {
        coEvery {
            messageRepository.closePoll(any(), any())
        } returns Result.failure(Exception("Not authorized"))

        val result = useCase("chat1", "msg1")

        assertTrue(result.isFailure)
    }
}
