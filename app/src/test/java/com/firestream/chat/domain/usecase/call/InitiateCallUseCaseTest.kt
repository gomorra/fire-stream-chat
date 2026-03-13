package com.firestream.chat.domain.usecase.call

import com.firestream.chat.domain.repository.CallRepository
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class InitiateCallUseCaseTest {

    private lateinit var callRepository: CallRepository
    private lateinit var useCase: InitiateCallUseCase

    @Before
    fun setUp() {
        callRepository = mockk()
        useCase = InitiateCallUseCase(callRepository)
    }

    @Test
    fun `invoke creates call and returns callId`() = runTest {
        coEvery { callRepository.createCall("callee123") } returns Result.success("call_abc")

        val result = useCase("callee123")

        assertTrue(result.isSuccess)
        assertEquals("call_abc", result.getOrNull())
        coVerify(exactly = 1) { callRepository.createCall("callee123") }
    }

    @Test
    fun `invoke returns failure when repository fails`() = runTest {
        coEvery { callRepository.createCall(any()) } returns Result.failure(Exception("Not authenticated"))

        val result = useCase("callee123")

        assertTrue(result.isFailure)
    }
}
