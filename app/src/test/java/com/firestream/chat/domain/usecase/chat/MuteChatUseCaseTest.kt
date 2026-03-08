package com.firestream.chat.domain.usecase.chat

import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import com.firestream.chat.domain.repository.ChatRepository
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class MuteChatUseCaseTest {

    private lateinit var chatRepository: ChatRepository
    private lateinit var useCase: MuteChatUseCase

    @Before
    fun setUp() {
        chatRepository = mockk()
        useCase = MuteChatUseCase(chatRepository)
    }

    @Test
    fun `invoke mutes chat for 1 hour`() = runTest {
        val muteUntil = System.currentTimeMillis() + 3_600_000L
        coEvery { chatRepository.muteChat("chat1", muteUntil) } returns Result.success(Unit)

        val result = useCase("chat1", muteUntil)

        assertTrue(result.isSuccess)
        coVerify(exactly = 1) { chatRepository.muteChat("chat1", muteUntil) }
    }

    @Test
    fun `invoke mutes chat permanently with MAX_VALUE`() = runTest {
        coEvery { chatRepository.muteChat("chat1", Long.MAX_VALUE) } returns Result.success(Unit)

        val result = useCase("chat1", Long.MAX_VALUE)

        assertTrue(result.isSuccess)
        coVerify { chatRepository.muteChat("chat1", Long.MAX_VALUE) }
    }

    @Test
    fun `invoke unmutes chat with zero`() = runTest {
        coEvery { chatRepository.muteChat("chat1", 0L) } returns Result.success(Unit)

        val result = useCase("chat1", 0L)

        assertTrue(result.isSuccess)
        coVerify { chatRepository.muteChat("chat1", 0L) }
    }

    @Test
    fun `invoke returns failure on error`() = runTest {
        coEvery { chatRepository.muteChat(any(), any()) } returns Result.failure(Exception("Error"))

        val result = useCase("chat1", Long.MAX_VALUE)

        assertTrue(result.isFailure)
    }
}
