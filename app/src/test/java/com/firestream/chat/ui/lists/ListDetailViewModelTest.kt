package com.firestream.chat.ui.lists

import androidx.lifecycle.SavedStateHandle
import com.firestream.chat.data.local.PreferencesDataStore
import com.firestream.chat.domain.model.ListData
import com.firestream.chat.domain.model.ListItem
import com.firestream.chat.domain.model.ListType
import com.firestream.chat.domain.usecase.list.SendListUpdateToChatsUseCase
import com.firestream.chat.domain.repository.AuthRepository
import com.firestream.chat.domain.repository.UserRepository
import com.firestream.chat.domain.repository.ChatRepository
import com.firestream.chat.domain.repository.ListRepository
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ListDetailViewModelTest {

    private val testDispatcher = StandardTestDispatcher()
    private val sendListUpdateToChatsUseCase = mockk<SendListUpdateToChatsUseCase>(relaxed = true)
    private val chatRepository = mockk<ChatRepository>()
    private val listRepository = mockk<ListRepository>(relaxed = true)
    private val authRepository = mockk<AuthRepository>()
    private val userRepository = mockk<UserRepository>()

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
        every { authRepository.currentUserId } returns "user1"
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
            authRepository = authRepository,
            userRepository = userRepository,
            preferencesDataStore = mockk<PreferencesDataStore>(relaxed = true),
            applicationScope = TestScope(testDispatcher),
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
        coEvery { listRepository.addItem("list1", any(), "Eggs", null, null) } returns Result.success(Unit)

        val viewModel = buildViewModel()
        runCurrent()

        viewModel.addItem("Eggs")
        runCurrent()

        coVerify(exactly = 1) { listRepository.addItem("list1", any(), "Eggs", null, null) }
    }

    @Test
    fun `addItem ignores blank text`() = runTest {
        every { listRepository.observeList("list1") } returns flowOf(ListData(id = "list1"))

        val viewModel = buildViewModel()
        runCurrent()

        viewModel.addItem("   ")
        runCurrent()

        coVerify(exactly = 0) { listRepository.addItem(any(), any(), any(), any(), any()) }
    }

    @Test
    fun `toggleItem calls repository with target checked state`() = runTest {
        val listData = ListData(
            id = "list1",
            items = listOf(ListItem(id = "i1", text = "Milk"))
        )
        every { listRepository.observeList("list1") } returns flowOf(listData)
        coEvery { listRepository.toggleItemChecked("list1", "i1", true) } returns Result.success(Unit)

        val viewModel = buildViewModel()
        runCurrent()

        viewModel.toggleItem("i1")
        runCurrent()

        coVerify(exactly = 1) { listRepository.toggleItemChecked("list1", "i1", true) }
    }

    @Test
    fun `clearCheckedItems optimistically removes checked items before repository call`() = runTest {
        val listData = ListData(
            id = "list1",
            items = listOf(
                ListItem(id = "i1", text = "Milk", isChecked = true),
                ListItem(id = "i2", text = "Eggs"),
                ListItem(id = "i3", text = "Bread", isChecked = true)
            )
        )
        every { listRepository.observeList("list1") } returns flowOf(listData)
        coEvery { listRepository.clearCheckedItems("list1") } returns Result.success(listOf("Milk", "Bread"))

        val viewModel = buildViewModel()
        runCurrent()

        viewModel.clearCheckedItems()

        // Before the coroutine runs, the optimistic update should already be visible
        val stateAfterCall = viewModel.uiState.value
        assertEquals(1, stateAfterCall.listData?.items?.size)
        assertEquals("Eggs", stateAfterCall.listData?.items?.firstOrNull()?.text)

        runCurrent()

        coVerify(exactly = 1) { listRepository.clearCheckedItems("list1") }
        // Final state still has only the unchecked item
        assertEquals(1, viewModel.uiState.value.listData?.items?.size)
    }

    @Test
    fun `clearCheckedItems reverts state on repository failure`() = runTest {
        val listData = ListData(
            id = "list1",
            items = listOf(
                ListItem(id = "i1", text = "Milk", isChecked = true),
                ListItem(id = "i2", text = "Eggs")
            )
        )
        every { listRepository.observeList("list1") } returns flowOf(listData)
        coEvery { listRepository.clearCheckedItems("list1") } returns Result.failure(Exception("boom"))

        val viewModel = buildViewModel()
        runCurrent()

        viewModel.clearCheckedItems()
        runCurrent()

        val state = viewModel.uiState.value
        assertEquals(2, state.listData?.items?.size)
        assertEquals("boom", state.error)
    }

    @Test
    fun `clearCheckedItems is a no-op when nothing is checked`() = runTest {
        val listData = ListData(
            id = "list1",
            items = listOf(ListItem(id = "i1", text = "Eggs"))
        )
        every { listRepository.observeList("list1") } returns flowOf(listData)

        val viewModel = buildViewModel()
        runCurrent()

        viewModel.clearCheckedItems()
        runCurrent()

        coVerify(exactly = 0) { listRepository.clearCheckedItems(any()) }
        assertEquals(1, viewModel.uiState.value.listData?.items?.size)
    }

    // ── Optimistic UI regression tests — the fix relies on every mutation showing
    //    immediately so users don't perceive sync lag while Firestore echoes back.

    @Test
    fun `addItem optimistically appends before repo resolves`() = runTest {
        every { listRepository.observeList("list1") } returns flowOf(ListData(id = "list1", itemCount = 0))
        // Suspends forever — we assert UI state BEFORE the repo returns.
        coEvery { listRepository.addItem("list1", any(), "Milk", null, null) } coAnswers {
            kotlinx.coroutines.awaitCancellation()
        }

        val viewModel = buildViewModel()
        runCurrent()

        viewModel.addItem("Milk")
        runCurrent()

        val state = viewModel.uiState.value.listData!!
        assertEquals(1, state.items.size)
        assertEquals("Milk", state.items.first().text)
        assertEquals(1, state.itemCount)
    }

    @Test
    fun `addItem reverts on repo failure`() = runTest {
        val initial = ListData(id = "list1", itemCount = 0)
        every { listRepository.observeList("list1") } returns flowOf(initial)
        coEvery { listRepository.addItem("list1", any(), "Milk", null, null) } returns Result.failure(Exception("boom"))

        val viewModel = buildViewModel()
        runCurrent()

        viewModel.addItem("Milk")
        runCurrent()

        val state = viewModel.uiState.value
        assertTrue("items should revert", state.listData?.items?.isEmpty() == true)
        assertEquals(0, state.listData?.itemCount)
        assertEquals("boom", state.error)
    }

    @Test
    fun `removeItem optimistically drops before repo resolves`() = runTest {
        val initial = ListData(
            id = "list1",
            items = listOf(
                ListItem(id = "i1", text = "Milk", isChecked = true),
                ListItem(id = "i2", text = "Eggs"),
            ),
            itemCount = 2,
            checkedCount = 1,
        )
        every { listRepository.observeList("list1") } returns flowOf(initial)
        coEvery { listRepository.removeItem("list1", "i1") } coAnswers { kotlinx.coroutines.awaitCancellation() }

        val viewModel = buildViewModel()
        runCurrent()

        viewModel.removeItem("i1")
        runCurrent()

        val state = viewModel.uiState.value.listData!!
        assertEquals(1, state.items.size)
        assertEquals("i2", state.items.first().id)
        assertEquals(1, state.itemCount)
        assertEquals(0, state.checkedCount)
    }

    @Test
    fun `rapid toggles all reflected in final UI state`() = runTest {
        val initial = ListData(
            id = "list1",
            items = (1..5).map { ListItem(id = "i$it", text = "t$it") },
            itemCount = 5,
        )
        every { listRepository.observeList("list1") } returns flowOf(initial)
        coEvery { listRepository.toggleItemChecked("list1", any(), any()) } returns Result.success(Unit)

        val viewModel = buildViewModel()
        runCurrent()

        (1..5).forEach { viewModel.toggleItem("i$it") }
        runCurrent()

        val state = viewModel.uiState.value.listData!!
        assertTrue("every optimistic toggle lands", state.items.all { it.isChecked })
        assertEquals(5, state.checkedCount)

        // Each call uses the target state computed from the latest local value.
        coVerify(exactly = 1) { listRepository.toggleItemChecked("list1", "i1", true) }
        coVerify(exactly = 1) { listRepository.toggleItemChecked("list1", "i5", true) }
    }

    @Test
    fun `updateItem optimistically rewrites before repo resolves`() = runTest {
        val initial = ListData(
            id = "list1",
            items = listOf(ListItem(id = "i1", text = "Milk")),
            itemCount = 1,
        )
        every { listRepository.observeList("list1") } returns flowOf(initial)
        coEvery { listRepository.updateItem("list1", "i1", "Oat milk", null, null) } coAnswers {
            kotlinx.coroutines.awaitCancellation()
        }

        val viewModel = buildViewModel()
        runCurrent()

        viewModel.updateItem("i1", "Oat milk")
        runCurrent()

        assertEquals("Oat milk", viewModel.uiState.value.listData?.items?.first()?.text)
    }

    @Test
    fun `initial null emissions do not flag the list as deleted`() = runTest {
        // Freshly shared list: Room is empty on first open and the Firestore listener's
        // first snapshot may also write null. The ViewModel must stay on loading until
        // real data arrives — if it flips isDeleted, ListDetailScreen pops back to the
        // chat and tapping a shared-list bubble appears to do nothing.
        every { listRepository.observeList("list1") } returns flowOf(null, null)

        val viewModel = buildViewModel()
        runCurrent()

        val state = viewModel.uiState.value
        assertFalse(state.isDeleted)
        assertFalse(state.isAccessDenied)
    }

    @Test
    fun `list vanishing after being loaded flags it deleted for the owner`() = runTest {
        val listData = ListData(id = "list1", title = "Groceries", createdBy = "user1")
        every { listRepository.observeList("list1") } returns flowOf(listData, null)

        val viewModel = buildViewModel()
        runCurrent()

        assertTrue(viewModel.uiState.value.isDeleted)
    }

    @Test
    fun `list vanishing after being loaded flags access denied for non-owner`() = runTest {
        val listData = ListData(
            id = "list1",
            title = "Groceries",
            createdBy = "other",
            participants = listOf("other", "user1")
        )
        every { listRepository.observeList("list1") } returns flowOf(listData, null)

        val viewModel = buildViewModel()
        runCurrent()

        assertTrue(viewModel.uiState.value.isAccessDenied)
    }

    @Test
    fun `deleteList calls repository and sets isDeleted with title`() = runTest {
        every { listRepository.observeList("list1") } returns flowOf(ListData(id = "list1", title = "Groceries"))
        coEvery { listRepository.deleteList("list1") } returns Result.success(Unit)

        val viewModel = buildViewModel()
        runCurrent()

        viewModel.deleteList()
        runCurrent()

        assertTrue(viewModel.uiState.value.isDeleted)
        assertEquals("Groceries", viewModel.uiState.value.deletedListTitle)
        coVerify(exactly = 1) { listRepository.deleteList("list1") }
    }
}
