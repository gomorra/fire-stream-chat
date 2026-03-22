package com.firestream.chat.domain.usecase.list

import com.firestream.chat.domain.model.ListData
import com.firestream.chat.domain.model.ListType
import com.firestream.chat.domain.repository.ListRepository
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class CreateListUseCaseTest {

    private lateinit var listRepository: ListRepository
    private lateinit var useCase: CreateListUseCase

    @Before
    fun setUp() {
        listRepository = mockk()
        useCase = CreateListUseCase(listRepository)
    }

    @Test
    fun `invoke creates list successfully`() = runTest {
        val listData = ListData(id = "list1", title = "Groceries", type = ListType.SHOPPING)
        coEvery { listRepository.createList("Groceries", ListType.SHOPPING, null) } returns Result.success(listData)

        val result = useCase("Groceries", ListType.SHOPPING)

        assertTrue(result.isSuccess)
        assertTrue(result.getOrNull()?.title == "Groceries")
        coVerify(exactly = 1) { listRepository.createList("Groceries", ListType.SHOPPING, null) }
    }

    @Test
    fun `invoke creates list with chatId`() = runTest {
        val listData = ListData(id = "list1", title = "Tasks", type = ListType.CHECKLIST)
        coEvery { listRepository.createList("Tasks", ListType.CHECKLIST, "chat1") } returns Result.success(listData)

        val result = useCase("Tasks", ListType.CHECKLIST, "chat1")

        assertTrue(result.isSuccess)
        coVerify(exactly = 1) { listRepository.createList("Tasks", ListType.CHECKLIST, "chat1") }
    }

    @Test
    fun `invoke returns failure when repository fails`() = runTest {
        coEvery { listRepository.createList(any(), any(), any()) } returns Result.failure(Exception("Error"))

        val result = useCase("Test", ListType.GENERIC)

        assertTrue(result.isFailure)
    }
}
