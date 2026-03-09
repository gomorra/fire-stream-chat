package com.firestream.chat.domain.usecase.chat

import com.firestream.chat.domain.model.GroupPermissions
import com.firestream.chat.domain.model.GroupRole
import com.firestream.chat.domain.repository.ChatRepository
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class UpdateGroupPermissionsUseCaseTest {

    private lateinit var chatRepository: ChatRepository
    private lateinit var useCase: UpdateGroupPermissionsUseCase

    @Before
    fun setUp() {
        chatRepository = mockk()
        useCase = UpdateGroupPermissionsUseCase(chatRepository)
    }

    @Test
    fun `invoke updates permissions successfully`() = runTest {
        val permissions = GroupPermissions(sendMessages = GroupRole.ADMIN, isAnnouncementMode = true)
        coEvery { chatRepository.updateGroupPermissions("chat1", permissions) } returns Result.success(Unit)

        val result = useCase("chat1", permissions)

        assertTrue(result.isSuccess)
        coVerify(exactly = 1) { chatRepository.updateGroupPermissions("chat1", permissions) }
    }

    @Test
    fun `invoke returns failure on repository error`() = runTest {
        val permissions = GroupPermissions()
        coEvery { chatRepository.updateGroupPermissions(any(), any()) } returns Result.failure(Exception("Error"))

        val result = useCase("chat1", permissions)

        assertTrue(result.isFailure)
    }
}
