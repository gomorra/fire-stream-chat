package com.firestream.chat.domain.usecase.list

import com.firestream.chat.domain.model.HistoryAction
import com.firestream.chat.domain.model.ListHistoryEntry
import com.firestream.chat.domain.repository.ListRepository
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

class ObserveListHistoryUseCaseTest {

    private lateinit var listRepository: ListRepository
    private lateinit var useCase: ObserveListHistoryUseCase

    @Before
    fun setUp() {
        listRepository = mockk()
        useCase = ObserveListHistoryUseCase(listRepository)
    }

    @Test
    fun `returns history from repository`() = runTest {
        val history = listOf(
            ListHistoryEntry(
                action = HistoryAction.CREATED,
                userId = "user1",
                userName = "Alice",
                timestamp = 1000L
            ),
            ListHistoryEntry(
                action = HistoryAction.ITEM_ADDED,
                itemText = "Milk",
                userId = "user2",
                userName = "Bob",
                timestamp = 2000L
            )
        )
        every { listRepository.observeHistory("list1") } returns flowOf(history)

        val result = useCase("list1").first()

        assertEquals(2, result.size)
        assertEquals(HistoryAction.CREATED, result[0].action)
        assertEquals("Milk", result[1].itemText)
    }

    @Test
    fun `returns empty list when no history`() = runTest {
        every { listRepository.observeHistory("list1") } returns flowOf(emptyList())

        val result = useCase("list1").first()

        assertEquals(0, result.size)
    }

    @Test
    fun `delegates to repository with correct listId`() = runTest {
        every { listRepository.observeHistory("abc") } returns flowOf(emptyList())

        useCase("abc").first()

        verify(exactly = 1) { listRepository.observeHistory("abc") }
    }
}
