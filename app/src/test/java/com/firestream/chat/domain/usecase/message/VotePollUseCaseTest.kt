package com.firestream.chat.domain.usecase.message

import com.firestream.chat.domain.repository.MessageRepository
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class VotePollUseCaseTest {

    private lateinit var messageRepository: MessageRepository
    private lateinit var useCase: VotePollUseCase

    @Before
    fun setUp() {
        messageRepository = mockk()
        useCase = VotePollUseCase(messageRepository)
    }

    @Test
    fun `invoke votes on poll successfully`() = runTest {
        coEvery {
            messageRepository.votePoll("chat1", "msg1", listOf("opt_0"))
        } returns Result.success(Unit)

        val result = useCase("chat1", "msg1", listOf("opt_0"))

        assertTrue(result.isSuccess)
        coVerify(exactly = 1) {
            messageRepository.votePoll("chat1", "msg1", listOf("opt_0"))
        }
    }

    @Test
    fun `invoke votes on multiple options`() = runTest {
        coEvery {
            messageRepository.votePoll("chat1", "msg1", listOf("opt_0", "opt_2"))
        } returns Result.success(Unit)

        val result = useCase("chat1", "msg1", listOf("opt_0", "opt_2"))

        assertTrue(result.isSuccess)
        coVerify(exactly = 1) {
            messageRepository.votePoll("chat1", "msg1", listOf("opt_0", "opt_2"))
        }
    }

    @Test
    fun `invoke returns failure when repository fails`() = runTest {
        coEvery {
            messageRepository.votePoll(any(), any(), any())
        } returns Result.failure(Exception("Not found"))

        val result = useCase("chat1", "msg1", listOf("opt_0"))

        assertTrue(result.isFailure)
    }
}
