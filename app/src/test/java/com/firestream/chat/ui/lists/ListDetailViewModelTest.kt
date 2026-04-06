package com.firestream.chat.ui.lists

import androidx.lifecycle.SavedStateHandle
import com.firestream.chat.domain.model.ListData
import com.firestream.chat.domain.model.ListItem
import com.firestream.chat.domain.model.ListType
import com.firestream.chat.domain.usecase.list.SendListUpdateToChatsUseCase
import com.firestream.chat.domain.repository.UserRepository
import com.firestream.chat.domain.repository.ChatRepository
import com.firestream.chat.domain.repository.ListRepository
import com.firestream.chat.data.remote.firebase.FirebaseAuthSource
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
    private val sendListUpdateToChatsUseCase = mockk<SendListUpdateToChatsUseCase>(relaxed = true)
    private val chatRepository = mockk<ChatRepository>()
    private val listRepository = mockk<ListRepository>(relaxed = true)
    private val authSource = mockk<FirebaseAuthSource>()
    private val userRepository = mockk<UserRepository>()

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

    private fun buildViewModel(): ListDetailViewModel {
        return ListDetailViewModel(
            savedStateHandle = SavedStateHandle(mapOf("listId" to "list1")),
            sendListUpdateToChatsUseCase = sendListUpdateToChatsUseCase,
            chatRepository = chatRepository,
            listRepository = listRepository,
            authSource = authSource,
            userRepository = userRepository
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
        every { listRepository.observeList("list1") } returns flowOf(listData)

        val viewModel = buildViewModel()
        runCurrent()

        val state = viewModel.uiState.value
        assertFalse(state.isLoading)
        assertEquals("Groceries", state.listData?.title)
        assertEquals(1, state.listData?.items?.size)
    }

    @Test
    fun `addItem calls repository`() = runTest {
        every { listRepository.observeList("list1") } returns flowOf(ListData(id = "list1"))
        coEvery { listRepository.addItem("list1", "Eggs", null, null) } returns Result.success(Unit)

        val viewModel = buildViewModel()
        runCurrent()

        viewModel.addItem("Eggs")
        runCurrent()

        coVerify(exactly = 1) { listRepository.addItem("list1", "Eggs", null, null) }
    }

    @Test
    fun `addItem ignores blank text`() = runTest {
        every { listRepository.observeList("list1") } returns flowOf(ListData(id = "list1"))

        val viewModel = buildViewModel()
        runCurrent()

        viewModel.addItem("   ")
        runCurrent()

        coVerify(exactly = 0) { listRepository.addItem(any(), any(), any(), any()) }
    }

    @Test
    fun `toggleItem calls repository`() = runTest {
        val listData = ListData(
            id = "list1",
            items = listOf(ListItem(id = "i1", text = "Milk"))
        )
        every { listRepository.observeList("list1") } returns flowOf(listData)
        coEvery { listRepository.toggleItemChecked("list1", "i1") } returns Result.success(Unit)

        val viewModel = buildViewModel()
        runCurrent()

        viewModel.toggleItem("i1")
        runCurrent()

        coVerify(exactly = 1) { listRepository.toggleItemChecked("list1", "i1") }
    }

    @Test
    fun `deleteList calls repository and sets isDeleted with title`() = runTest {
        every { listRepository.observeList("list1") } returns flowOf(ListData(id = "list1", title = "Groceries"))
        coEvery { listRepository.deleteList("list1") } returns Result.success(Unit)

        val viewModel = buildViewModel()
        runCurrent()

        viewModel.deleteList()
        runCurrent()

        assert(viewModel.uiState.value.isDeleted)
        assertEquals("Groceries", viewModel.uiState.value.deletedListTitle)
        coVerify(exactly = 1) { listRepository.deleteList("list1") }
    }
}
