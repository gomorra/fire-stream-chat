package com.firestream.chat.domain.usecase.chat

import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import com.firestream.chat.domain.repository.ChatRepository
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class UpdateGroupDescriptionUseCaseTest {

    private lateinit var chatRepository: ChatRepository
    private lateinit var useCase: UpdateGroupDescriptionUseCase

    @Before
    fun setUp() {
        chatRepository = mockk()
        useCase = UpdateGroupDescriptionUseCase(chatRepository)
    }

    @Test
    fun `invoke updates description successfully`() = runTest {
        coEvery { chatRepository.updateGroupDescription("chat1", "New description") } returns Result.success(Unit)

        val result = useCase("chat1", "New description")

        assertTrue(result.isSuccess)
        coVerify(exactly = 1) { chatRepository.updateGroupDescription("chat1", "New description") }
    }

    @Test
    fun `invoke returns failure on repository error`() = runTest {
        coEvery { chatRepository.updateGroupDescription(any(), any()) } returns Result.failure(Exception("Network error"))

        val result = useCase("chat1", "desc")

        assertTrue(result.isFailure)
    }
}
