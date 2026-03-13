package com.firestream.chat.domain.usecase.call

import com.firestream.chat.domain.repository.CallRepository
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class AnswerCallUseCaseTest {

    private lateinit var callRepository: CallRepository
    private lateinit var useCase: AnswerCallUseCase

    @Before
    fun setUp() {
        callRepository = mockk()
        useCase = AnswerCallUseCase(callRepository)
    }

    @Test
    fun `invoke answers call successfully`() = runTest {
        coEvery { callRepository.answerCall("call123") } returns Result.success(Unit)

        val result = useCase("call123")

        assertTrue(result.isSuccess)
        coVerify(exactly = 1) { callRepository.answerCall("call123") }
    }

    @Test
    fun `invoke returns failure when repository fails`() = runTest {
        coEvery { callRepository.answerCall(any()) } returns Result.failure(Exception("Network error"))

        val result = useCase("call123")

        assertTrue(result.isFailure)
    }
}
