package com.firestream.chat.domain.usecase.list

import com.firestream.chat.domain.repository.ListRepository
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class AddListItemUseCaseTest {

    private lateinit var listRepository: ListRepository
    private lateinit var useCase: AddListItemUseCase

    @Before
    fun setUp() {
        listRepository = mockk()
        useCase = AddListItemUseCase(listRepository)
    }

    @Test
    fun `invoke adds item successfully`() = runTest {
        coEvery { listRepository.addItem("list1", "Milk", null, null) } returns Result.success(Unit)

        val result = useCase("list1", "Milk")

        assertTrue(result.isSuccess)
        coVerify(exactly = 1) { listRepository.addItem("list1", "Milk", null, null) }
    }

    @Test
    fun `invoke adds item with quantity and unit`() = runTest {
        coEvery { listRepository.addItem("list1", "Milk", "2", "L") } returns Result.success(Unit)

        val result = useCase("list1", "Milk", "2", "L")

        assertTrue(result.isSuccess)
    }

    @Test
    fun `invoke returns failure when repository fails`() = runTest {
        coEvery { listRepository.addItem(any(), any(), any(), any()) } returns Result.failure(Exception("Error"))

        val result = useCase("list1", "Milk")

        assertTrue(result.isFailure)
    }
}
