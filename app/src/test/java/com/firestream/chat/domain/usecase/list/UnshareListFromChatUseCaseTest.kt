package com.firestream.chat.domain.usecase.list

import com.firestream.chat.domain.repository.ListRepository
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class UnshareListFromChatUseCaseTest {

    private lateinit var listRepository: ListRepository
    private lateinit var useCase: UnshareListFromChatUseCase

    @Before
    fun setUp() {
        listRepository = mockk()
        useCase = UnshareListFromChatUseCase(listRepository)
    }

    @Test
    fun `invoke unshares list from chat successfully`() = runTest {
        coEvery { listRepository.unshareListFromChat("list1", "chat1") } returns Result.success(Unit)

        val result = useCase("list1", "chat1")

        assertTrue(result.isSuccess)
        coVerify(exactly = 1) { listRepository.unshareListFromChat("list1", "chat1") }
    }

    @Test
    fun `invoke returns failure when repository fails`() = runTest {
        coEvery { listRepository.unshareListFromChat(any(), any()) } returns Result.failure(Exception("Error"))

        val result = useCase("list1", "chat1")

        assertTrue(result.isFailure)
    }
}
