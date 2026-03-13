package com.firestream.chat.domain.usecase.call

import com.firestream.chat.domain.repository.CallRepository
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class EndCallUseCaseTest {

    private lateinit var callRepository: CallRepository
    private lateinit var useCase: EndCallUseCase

    @Before
    fun setUp() {
        callRepository = mockk()
        useCase = EndCallUseCase(callRepository)
    }

    @Test
    fun `invoke ends call with default reason`() = runTest {
        coEvery { callRepository.endCall("call123", "caller_hangup") } returns Result.success(Unit)

        val result = useCase("call123")

        assertTrue(result.isSuccess)
        coVerify(exactly = 1) { callRepository.endCall("call123", "caller_hangup") }
    }

    @Test
    fun `invoke ends call with custom reason`() = runTest {
        coEvery { callRepository.endCall("call123", "timeout") } returns Result.success(Unit)

        val result = useCase("call123", "timeout")

        assertTrue(result.isSuccess)
        coVerify(exactly = 1) { callRepository.endCall("call123", "timeout") }
    }

    @Test
    fun `invoke returns failure when repository fails`() = runTest {
        coEvery { callRepository.endCall(any(), any()) } returns Result.failure(Exception("Firestore error"))

        val result = useCase("call123")

        assertTrue(result.isFailure)
    }
}
