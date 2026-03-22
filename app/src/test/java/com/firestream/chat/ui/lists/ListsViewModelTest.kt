package com.firestream.chat.ui.lists

import com.firestream.chat.domain.model.ListData
import com.firestream.chat.domain.model.ListType
import com.firestream.chat.domain.usecase.list.CreateListUseCase
import com.firestream.chat.domain.usecase.list.ObserveMyListsUseCase
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ListsViewModelTest {

    private val testDispatcher = StandardTestDispatcher()
    private val observeMyListsUseCase = mockk<ObserveMyListsUseCase>()
    private val createListUseCase = mockk<CreateListUseCase>()

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `observes lists on init`() = runTest {
        val lists = listOf(
            ListData(id = "1", title = "Groceries", type = ListType.SHOPPING),
            ListData(id = "2", title = "Tasks", type = ListType.CHECKLIST)
        )
        every { observeMyListsUseCase() } returns flowOf(lists)

        val viewModel = ListsViewModel(observeMyListsUseCase, createListUseCase)
        runCurrent()

        val state = viewModel.uiState.value
        assertFalse(state.isLoading)
        assertEquals(2, state.lists.size)
        assertEquals("Groceries", state.lists[0].title)
    }

    @Test
    fun `createList calls use case and invokes callback`() = runTest {
        every { observeMyListsUseCase() } returns flowOf(emptyList())
        val created = ListData(id = "new1", title = "New List", type = ListType.GENERIC)
        coEvery { createListUseCase("New List", ListType.GENERIC, null) } returns Result.success(created)

        val viewModel = ListsViewModel(observeMyListsUseCase, createListUseCase)
        runCurrent()

        var callbackId = ""
        viewModel.createList("New List", ListType.GENERIC) { callbackId = it }
        runCurrent()

        assertEquals("new1", callbackId)
    }

    @Test
    fun `createList failure sets error`() = runTest {
        every { observeMyListsUseCase() } returns flowOf(emptyList())
        coEvery { createListUseCase(any(), any(), any()) } returns Result.failure(Exception("Oops"))

        val viewModel = ListsViewModel(observeMyListsUseCase, createListUseCase)
        runCurrent()

        viewModel.createList("Fail", ListType.CHECKLIST) {}
        runCurrent()

        assertEquals("Oops", viewModel.uiState.value.error)
    }
}
