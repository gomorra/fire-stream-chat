package com.firestream.chat.domain.usecase.message

import com.firestream.chat.domain.repository.PollRepository
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class VotePollUseCaseTest {

    private lateinit var pollRepository: PollRepository
    private lateinit var useCase: VotePollUseCase

    @Before
    fun setUp() {
        pollRepository = mockk()
        useCase = VotePollUseCase(pollRepository)
    }

    @Test
    fun `invoke votes on poll successfully`() = runTest {
        coEvery {
            pollRepository.votePoll("chat1", "msg1", listOf("opt_0"))
        } returns Result.success(Unit)

        val result = useCase("chat1", "msg1", listOf("opt_0"))

        assertTrue(result.isSuccess)
        coVerify(exactly = 1) {
            pollRepository.votePoll("chat1", "msg1", listOf("opt_0"))
        }
    }

    @Test
    fun `invoke votes on multiple options`() = runTest {
        coEvery {
            pollRepository.votePoll("chat1", "msg1", listOf("opt_0", "opt_2"))
        } returns Result.success(Unit)

        val result = useCase("chat1", "msg1", listOf("opt_0", "opt_2"))

        assertTrue(result.isSuccess)
        coVerify(exactly = 1) {
            pollRepository.votePoll("chat1", "msg1", listOf("opt_0", "opt_2"))
        }
    }

    @Test
    fun `invoke returns failure when repository fails`() = runTest {
        coEvery {
            pollRepository.votePoll(any(), any(), any())
        } returns Result.failure(Exception("Not found"))

        val result = useCase("chat1", "msg1", listOf("opt_0"))

        assertTrue(result.isFailure)
    }
}
