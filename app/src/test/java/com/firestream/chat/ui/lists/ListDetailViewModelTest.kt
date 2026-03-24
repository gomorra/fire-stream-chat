package com.firestream.chat.ui.lists

import androidx.lifecycle.SavedStateHandle
import com.firestream.chat.domain.model.ListData
import com.firestream.chat.domain.model.ListItem
import com.firestream.chat.domain.model.ListType
import com.firestream.chat.domain.usecase.list.AddListItemUseCase
import com.firestream.chat.domain.usecase.list.DeleteListUseCase
import com.firestream.chat.domain.usecase.list.ObserveListUseCase
import com.firestream.chat.domain.usecase.list.RemoveListItemUseCase
import com.firestream.chat.domain.usecase.list.ShareListToChatUseCase
import com.firestream.chat.domain.usecase.list.ToggleListItemUseCase
import com.firestream.chat.domain.usecase.list.UpdateListItemUseCase
import com.firestream.chat.domain.usecase.list.UpdateListTitleUseCase
import com.firestream.chat.domain.usecase.list.UpdateListTypeUseCase
import io.mockk.coEvery
import io.mockk.coVerify
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
class ListDetailViewModelTest {

    private val testDispatcher = StandardTestDispatcher()
    private val observeListUseCase = mockk<ObserveListUseCase>()
    private val addListItemUseCase = mockk<AddListItemUseCase>()
    private val removeListItemUseCase = mockk<RemoveListItemUseCase>()
    private val toggleListItemUseCase = mockk<ToggleListItemUseCase>()
    private val updateListItemUseCase = mockk<UpdateListItemUseCase>()
    private val updateListTitleUseCase = mockk<UpdateListTitleUseCase>()
    private val updateListTypeUseCase = mockk<UpdateListTypeUseCase>()
    private val shareListToChatUseCase = mockk<ShareListToChatUseCase>()
    private val deleteListUseCase = mockk<DeleteListUseCase>()

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun buildViewModel(): ListDetailViewModel {
        return ListDetailViewModel(
            savedStateHandle = SavedStateHandle(mapOf("listId" to "list1")),
            observeListUseCase = observeListUseCase,
            addListItemUseCase = addListItemUseCase,
            removeListItemUseCase = removeListItemUseCase,
            toggleListItemUseCase = toggleListItemUseCase,
            updateListItemUseCase = updateListItemUseCase,
            updateListTitleUseCase = updateListTitleUseCase,
            updateListTypeUseCase = updateListTypeUseCase,
            shareListToChatUseCase = shareListToChatUseCase,
            deleteListUseCase = deleteListUseCase
        )
    }

    @Test
    fun `observes list on init`() = runTest {
        val listData = ListData(
            id = "list1",
            title = "Groceries",
            type = ListType.SHOPPING,
            items = listOf(ListItem(id = "i1", text = "Milk"))
        )
        every { observeListUseCase("list1") } returns flowOf(listData)

        val viewModel = buildViewModel()
        runCurrent()

        val state = viewModel.uiState.value
        assertFalse(state.isLoading)
        assertEquals("Groceries", state.listData?.title)
        assertEquals(1, state.listData?.items?.size)
    }

    @Test
    fun `addItem calls use case`() = runTest {
        every { observeListUseCase("list1") } returns flowOf(ListData(id = "list1"))
        coEvery { addListItemUseCase("list1", "Eggs", null, null) } returns Result.success(Unit)

        val viewModel = buildViewModel()
        runCurrent()

        viewModel.addItem("Eggs")
        runCurrent()

        coVerify(exactly = 1) { addListItemUseCase("list1", "Eggs", null, null) }
    }

    @Test
    fun `addItem ignores blank text`() = runTest {
        every { observeListUseCase("list1") } returns flowOf(ListData(id = "list1"))

        val viewModel = buildViewModel()
        runCurrent()

        viewModel.addItem("   ")
        runCurrent()

        coVerify(exactly = 0) { addListItemUseCase(any(), any(), any(), any()) }
    }

    @Test
    fun `toggleItem calls use case`() = runTest {
        every { observeListUseCase("list1") } returns flowOf(ListData(id = "list1"))
        coEvery { toggleListItemUseCase("list1", "i1") } returns Result.success(Unit)

        val viewModel = buildViewModel()
        runCurrent()

        viewModel.toggleItem("i1")
        runCurrent()

        coVerify(exactly = 1) { toggleListItemUseCase("list1", "i1") }
    }

    @Test
    fun `deleteList calls use case and sets isDeleted`() = runTest {
        every { observeListUseCase("list1") } returns flowOf(ListData(id = "list1"))
        coEvery { deleteListUseCase("list1") } returns Result.success(Unit)

        val viewModel = buildViewModel()
        runCurrent()

        viewModel.deleteList()
        runCurrent()

        assert(viewModel.uiState.value.isDeleted)
        coVerify(exactly = 1) { deleteListUseCase("list1") }
    }
}
