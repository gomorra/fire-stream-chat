package com.firestream.chat.domain.usecase.chat

import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import com.firestream.chat.domain.repository.ChatRepository
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class ArchiveChatUseCaseTest {

    private lateinit var chatRepository: ChatRepository
    private lateinit var useCase: ArchiveChatUseCase

    @Before
    fun setUp() {
        chatRepository = mockk()
        useCase = ArchiveChatUseCase(chatRepository)
    }

    @Test
    fun `invoke archives chat successfully`() = runTest {
        coEvery { chatRepository.archiveChat("chat1", true) } returns Result.success(Unit)

        val result = useCase("chat1", true)

        assertTrue(result.isSuccess)
        coVerify(exactly = 1) { chatRepository.archiveChat("chat1", true) }
    }

    @Test
    fun `invoke unarchives chat successfully`() = runTest {
        coEvery { chatRepository.archiveChat("chat1", false) } returns Result.success(Unit)

        val result = useCase("chat1", false)

        assertTrue(result.isSuccess)
        coVerify(exactly = 1) { chatRepository.archiveChat("chat1", false) }
    }

    @Test
    fun `invoke returns failure on repository error`() = runTest {
        coEvery { chatRepository.archiveChat(any(), any()) } returns Result.failure(Exception("DB error"))

        val result = useCase("chat1", true)

        assertTrue(result.isFailure)
    }
}
