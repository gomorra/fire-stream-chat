package com.firestream.chat.domain.usecase.call

import com.firestream.chat.domain.repository.CallRepository
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class DeclineCallUseCaseTest {

    private lateinit var callRepository: CallRepository
    private lateinit var useCase: DeclineCallUseCase

    @Before
    fun setUp() {
        callRepository = mockk()
        useCase = DeclineCallUseCase(callRepository)
    }

    @Test
    fun `invoke declines call successfully`() = runTest {
        coEvery { callRepository.declineCall("call123") } returns Result.success(Unit)

        val result = useCase("call123")

        assertTrue(result.isSuccess)
        coVerify(exactly = 1) { callRepository.declineCall("call123") }
    }

    @Test
    fun `invoke returns failure when repository fails`() = runTest {
        coEvery { callRepository.declineCall(any()) } returns Result.failure(Exception("Firestore error"))

        val result = useCase("call123")

        assertTrue(result.isFailure)
    }
}
