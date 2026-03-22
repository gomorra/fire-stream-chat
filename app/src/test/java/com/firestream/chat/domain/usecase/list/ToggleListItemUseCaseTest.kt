package com.firestream.chat.domain.usecase.list

import com.firestream.chat.domain.repository.ListRepository
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class ToggleListItemUseCaseTest {

    private lateinit var listRepository: ListRepository
    private lateinit var useCase: ToggleListItemUseCase

    @Before
    fun setUp() {
        listRepository = mockk()
        useCase = ToggleListItemUseCase(listRepository)
    }

    @Test
    fun `invoke toggles item successfully`() = runTest {
        coEvery { listRepository.toggleItemChecked("list1", "item1") } returns Result.success(Unit)

        val result = useCase("list1", "item1")

        assertTrue(result.isSuccess)
        coVerify(exactly = 1) { listRepository.toggleItemChecked("list1", "item1") }
    }

    @Test
    fun `invoke returns failure when repository fails`() = runTest {
        coEvery { listRepository.toggleItemChecked(any(), any()) } returns Result.failure(Exception("Error"))

        val result = useCase("list1", "item1")

        assertTrue(result.isFailure)
    }
}
