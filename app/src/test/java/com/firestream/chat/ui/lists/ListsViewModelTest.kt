package com.firestream.chat.ui.lists

import com.firestream.chat.data.local.PreferencesDataStore
import com.firestream.chat.data.remote.firebase.FirebaseAuthSource
import com.firestream.chat.domain.model.GenericListStyle
import com.firestream.chat.domain.model.ListData
import com.firestream.chat.domain.model.ListType
import com.firestream.chat.domain.repository.ChatRepository
import com.firestream.chat.domain.repository.ListRepository
import com.firestream.chat.domain.repository.UserRepository
import io.mockk.coEvery
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
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ListsViewModelTest {

    private val testDispatcher = StandardTestDispatcher()
    private val listRepository = mockk<ListRepository> {
        every { observeList(any()) } returns flowOf(null)
    }
    private val chatRepository = mockk<ChatRepository>()
    private val authSource = mockk<FirebaseAuthSource>()
    private val userRepository = mockk<UserRepository>()
    private val preferencesDataStore = mockk<PreferencesDataStore>()

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
        every { authSource.currentUserId } returns "user1"
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
        authSource = authSource,
        userRepository = userRepository,
        preferencesDataStore = preferencesDataStore
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
}
