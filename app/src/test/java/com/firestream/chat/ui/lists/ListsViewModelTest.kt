package com.firestream.chat.ui.lists

import com.firestream.chat.data.remote.firebase.FirebaseAuthSource
import com.firestream.chat.domain.model.ListData
import com.firestream.chat.domain.model.ListType
import com.firestream.chat.domain.repository.ChatRepository
import com.firestream.chat.domain.usecase.list.CreateListUseCase
import com.firestream.chat.domain.usecase.list.DeleteListUseCase
import com.firestream.chat.domain.usecase.list.ObserveListHistoryUseCase
import com.firestream.chat.domain.usecase.list.ObserveMyListsUseCase
import com.firestream.chat.domain.usecase.list.ShareListToChatUseCase
import com.firestream.chat.domain.usecase.list.UpdateListTitleUseCase
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
    private val shareListToChatUseCase = mockk<ShareListToChatUseCase>()
    private val deleteListUseCase = mockk<DeleteListUseCase>()
    private val updateListTitleUseCase = mockk<UpdateListTitleUseCase>()
    private val observeListHistoryUseCase = mockk<ObserveListHistoryUseCase>()
    private val chatRepository = mockk<ChatRepository>()
    private val authSource = mockk<FirebaseAuthSource>()

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
        every { authSource.currentUserId } returns "user1"
        every { chatRepository.getChats() } returns flowOf(emptyList())
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun createViewModel(): ListsViewModel = ListsViewModel(
        observeMyListsUseCase = observeMyListsUseCase,
        createListUseCase = createListUseCase,
        shareListToChatUseCase = shareListToChatUseCase,
        deleteListUseCase = deleteListUseCase,
        updateListTitleUseCase = updateListTitleUseCase,
        observeListHistoryUseCase = observeListHistoryUseCase,
        chatRepository = chatRepository,
        authSource = authSource
    )

    @Test
    fun `observes lists on init`() = runTest {
        val lists = listOf(
            ListData(id = "1", title = "Groceries", type = ListType.SHOPPING),
            ListData(id = "2", title = "Tasks", type = ListType.CHECKLIST)
        )
        every { observeMyListsUseCase() } returns flowOf(lists)

        val viewModel = createViewModel()
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

        val viewModel = createViewModel()
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

        val viewModel = createViewModel()
        runCurrent()

        viewModel.createList("Fail", ListType.CHECKLIST) {}
        runCurrent()

        assertEquals("Oops", viewModel.uiState.value.error)
    }
}
