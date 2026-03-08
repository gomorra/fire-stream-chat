package com.firestream.chat.domain.usecase.chat

import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import com.firestream.chat.domain.repository.ChatRepository
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class PinChatUseCaseTest {

    private lateinit var chatRepository: ChatRepository
    private lateinit var useCase: PinChatUseCase

    @Before
    fun setUp() {
        chatRepository = mockk()
        useCase = PinChatUseCase(chatRepository)
    }

    @Test
    fun `invoke calls pinChat with correct arguments when pinning`() = runTest {
        coEvery { chatRepository.pinChat("chat1", true) } returns Result.success(Unit)

        val result = useCase("chat1", true)

        assertTrue(result.isSuccess)
        coVerify(exactly = 1) { chatRepository.pinChat("chat1", true) }
    }

    @Test
    fun `invoke calls pinChat with correct arguments when unpinning`() = runTest {
        coEvery { chatRepository.pinChat("chat1", false) } returns Result.success(Unit)

        val result = useCase("chat1", false)

        assertTrue(result.isSuccess)
        coVerify(exactly = 1) { chatRepository.pinChat("chat1", false) }
    }

    @Test
    fun `invoke returns failure when repository throws`() = runTest {
        val error = Exception("Network error")
        coEvery { chatRepository.pinChat(any(), any()) } returns Result.failure(error)

        val result = useCase("chat1", true)

        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull() is Exception)
    }
}
