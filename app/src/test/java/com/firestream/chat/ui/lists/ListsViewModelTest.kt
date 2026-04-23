package com.firestream.chat.ui.lists

import com.firestream.chat.data.local.PreferencesDataStore
import com.firestream.chat.domain.model.GenericListStyle
import com.firestream.chat.domain.model.ListData
import com.firestream.chat.domain.model.ListType
import com.firestream.chat.domain.repository.AuthRepository
import com.firestream.chat.domain.repository.ChatRepository
import com.firestream.chat.domain.repository.ListRepository
import com.firestream.chat.domain.repository.UserRepository
import com.firestream.chat.domain.usecase.list.SendListUpdateToChatsUseCase
import com.firestream.chat.domain.model.ListDiff
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ListsViewModelTest {

    private val testDispatcher = StandardTestDispatcher()
    private val listRepository = mockk<ListRepository> {
        every { observeList(any()) } returns flowOf(null)
    }
    private val chatRepository = mockk<ChatRepository>()
    private val authRepository = mockk<AuthRepository>()
    private val userRepository = mockk<UserRepository>()
    private val preferencesDataStore = mockk<PreferencesDataStore>()
    private val sendListUpdateToChatsUseCase = mockk<SendListUpdateToChatsUseCase>()

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
        every { authRepository.currentUserId } returns "user1"
        every { chatRepository.getChats() } returns flowOf(emptyList())
        every { preferencesDataStore.listSortOptionFlow } returns MutableStateFlow("MODIFIED")
        every { preferencesDataStore.pinnedListIdsFlow } returns MutableStateFlow(emptySet())
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun createViewModel(): ListsViewModel = ListsViewModel(
        listRepository = listRepository,
        chatRepository = chatRepository,
        authRepository = authRepository,
        userRepository = userRepository,
        preferencesDataStore = preferencesDataStore,
        sendListUpdateToChatsUseCase = sendListUpdateToChatsUseCase
    )

    @Test
    fun `observes lists on init`() = runTest {
        val lists = listOf(
            ListData(id = "1", title = "Groceries", type = ListType.SHOPPING),
            ListData(id = "2", title = "Tasks", type = ListType.CHECKLIST)
        )
        every { listRepository.observeMyLists() } returns flowOf(lists)

        val viewModel = createViewModel()
        runCurrent()

        val state = viewModel.uiState.value
        assertFalse(state.isLoading)
        assertEquals(2, state.lists.size)
        assertEquals("Groceries", state.lists[0].title)
    }

    @Test
    fun `createList calls repository and invokes callback`() = runTest {
        every { listRepository.observeMyLists() } returns flowOf(emptyList())
        val created = ListData(id = "new1", title = "New List", type = ListType.GENERIC)
        coEvery { listRepository.createList("New List", ListType.GENERIC, genericStyle = GenericListStyle.BULLET) } returns Result.success(created)

        val viewModel = createViewModel()
        runCurrent()

        var callbackId = ""
        viewModel.createList("New List", ListType.GENERIC) { callbackId = it }
        runCurrent()

        assertEquals("new1", callbackId)
    }

    @Test
    fun `createList failure sets error`() = runTest {
        every { listRepository.observeMyLists() } returns flowOf(emptyList())
        coEvery { listRepository.createList(any(), any(), any(), any()) } returns Result.failure(Exception("Oops"))

        val viewModel = createViewModel()
        runCurrent()

        viewModel.createList("Fail", ListType.CHECKLIST) {}
        runCurrent()

        assertEquals("Oops", viewModel.uiState.value.error)
    }

    @Test
    fun `deleteList shows snackbar and notifies shared users`() = runTest {
        val lists = listOf(
            ListData(
                id = "1", title = "Groceries", type = ListType.SHOPPING,
                createdBy = "user1", sharedChatIds = listOf("chat1", "chat2")
            )
        )
        every { listRepository.observeMyLists() } returns flowOf(lists)
        coEvery { listRepository.deleteList("1") } returns Result.success(Unit)
        coEvery { sendListUpdateToChatsUseCase(any(), any(), any(), any()) } returns Unit

        val viewModel = createViewModel()
        runCurrent()

        viewModel.deleteList("1")
        runCurrent()

        assertEquals("\"Groceries\" deleted", viewModel.uiState.value.snackbarMessage)
        coVerify(exactly = 1) {
            sendListUpdateToChatsUseCase(
                "1", "Groceries", listOf("chat1", "chat2"),
                match { it.deleted }
            )
        }
    }

    @Test
    fun `deleteList without shared chats skips notification`() = runTest {
        val lists = listOf(
            ListData(id = "1", title = "Tasks", type = ListType.CHECKLIST, createdBy = "user1")
        )
        every { listRepository.observeMyLists() } returns flowOf(lists)
        coEvery { listRepository.deleteList("1") } returns Result.success(Unit)

        val viewModel = createViewModel()
        runCurrent()

        viewModel.deleteList("1")
        runCurrent()

        assertEquals("\"Tasks\" deleted", viewModel.uiState.value.snackbarMessage)
        coVerify(exactly = 0) { sendListUpdateToChatsUseCase(any(), any(), any(), any()) }
    }

    @Test
    fun `deleteList failure sets error`() = runTest {
        val lists = listOf(
            ListData(id = "1", title = "Tasks", type = ListType.CHECKLIST, createdBy = "user1")
        )
        every { listRepository.observeMyLists() } returns flowOf(lists)
        coEvery { listRepository.deleteList("1") } returns Result.failure(Exception("Network error"))

        val viewModel = createViewModel()
        runCurrent()

        viewModel.deleteList("1")
        runCurrent()

        assertEquals("Network error", viewModel.uiState.value.error)
        assertNull(viewModel.uiState.value.snackbarMessage)
    }
}
