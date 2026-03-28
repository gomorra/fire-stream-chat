package com.firestream.chat.domain.usecase.message

import com.firestream.chat.domain.repository.PollRepository
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class ClosePollUseCaseTest {

    private lateinit var pollRepository: PollRepository
    private lateinit var useCase: ClosePollUseCase

    @Before
    fun setUp() {
        pollRepository = mockk()
        useCase = ClosePollUseCase(pollRepository)
    }

    @Test
    fun `invoke closes poll successfully`() = runTest {
        coEvery { pollRepository.closePoll("chat1", "msg1") } returns Result.success(Unit)

        val result = useCase("chat1", "msg1")

        assertTrue(result.isSuccess)
        coVerify(exactly = 1) { pollRepository.closePoll("chat1", "msg1") }
    }

    @Test
    fun `invoke returns failure when repository fails`() = runTest {
        coEvery {
            pollRepository.closePoll(any(), any())
        } returns Result.failure(Exception("Not authorized"))

        val result = useCase("chat1", "msg1")

        assertTrue(result.isFailure)
    }
}
