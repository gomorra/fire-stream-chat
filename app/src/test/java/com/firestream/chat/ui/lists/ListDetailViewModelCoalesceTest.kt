package com.firestream.chat.ui.lists

import androidx.lifecycle.SavedStateHandle
import com.firestream.chat.data.remote.firebase.FirebaseAuthSource
import com.firestream.chat.domain.model.ListData
import com.firestream.chat.domain.model.ListDiff
import com.firestream.chat.domain.repository.ChatRepository
import com.firestream.chat.domain.repository.ListRepository
import com.firestream.chat.domain.repository.UserRepository
import com.firestream.chat.domain.usecase.list.SendListUpdateToChatsUseCase
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Before
import org.junit.Test

/**
 * Locks down the sender-side "list edit → debounce → chat bubble" path that
 * previously regressed without any unit test catching it.
 *
 * The existing [com.firestream.chat.data.repository.MessageRepositoryListMergeTest]
 * only covers [com.firestream.chat.data.repository.MessageRepositoryImpl.sendListMessage]
 * in isolation; it cannot catch breakage in the
 * [ListDetailViewModel] → [SendListUpdateToChatsUseCase] → repository wiring.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ListDetailViewModelCoalesceTest {

    // Must stay in sync with `DEBOUNCE_MS` in ListDetailViewModel.
    private val debounceMs = 10_000L

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

    private fun buildViewModel() = ListDetailViewModel(
        savedStateHandle = SavedStateHandle(mapOf("listId" to "list1")),
        sendListUpdateToChatsUseCase = sendListUpdateToChatsUseCase,
        chatRepository = chatRepository,
        listRepository = listRepository,
        authSource = authSource,
        userRepository = userRepository,
        applicationScope = TestScope(testDispatcher),
    )

    private fun sharedList(sharedChatIds: List<String> = listOf("chat1")) = ListData(
        id = "list1",
        title = "Groceries",
        sharedChatIds = sharedChatIds,
    )

    @Test
    fun `adding an item triggers the list-update use case after the debounce`() = runTest {
        every { listRepository.observeList("list1") } returns flowOf(sharedList())
        coEvery { listRepository.addItem("list1", "Bread", null, null) } returns Result.success(Unit)

        val viewModel = buildViewModel()
        runCurrent()

        viewModel.addItem("Bread")
        runCurrent()

        // Before the debounce fires, no chat bubble update has been sent.
        coVerify(exactly = 0) {
            sendListUpdateToChatsUseCase(any(), any(), any(), any())
        }

        advanceTimeBy(debounceMs + 100)
        runCurrent()

        // Regression anchor: after the debounce, exactly one update is sent
        // with the accumulated diff for every shared chat.
        coVerify(exactly = 1) {
            sendListUpdateToChatsUseCase(
                "list1",
                "Groceries",
                listOf("chat1"),
                ListDiff(added = listOf("Bread")),
            )
        }
    }

    @Test
    fun `multiple rapid edits coalesce into one accumulated use case call`() = runTest {
        every { listRepository.observeList("list1") } returns flowOf(sharedList())
        coEvery { listRepository.addItem("list1", any(), null, null) } returns Result.success(Unit)

        val viewModel = buildViewModel()
        runCurrent()

        viewModel.addItem("Bread")
        runCurrent()
        advanceTimeBy(1_000)
        viewModel.addItem("Milk")
        runCurrent()
        advanceTimeBy(1_000)
        viewModel.addItem("Eggs")
        runCurrent()

        advanceTimeBy(debounceMs + 100)
        runCurrent()

        coVerify(exactly = 1) {
            sendListUpdateToChatsUseCase(
                "list1",
                "Groceries",
                listOf("chat1"),
                ListDiff(added = listOf("Bread", "Milk", "Eggs")),
            )
        }
    }

    @Test
    fun `fanout hits every shared chat once`() = runTest {
        every { listRepository.observeList("list1") } returns
            flowOf(sharedList(sharedChatIds = listOf("chat1", "chat2", "chat3")))
        coEvery { listRepository.addItem("list1", "Bread", null, null) } returns Result.success(Unit)

        val viewModel = buildViewModel()
        runCurrent()

        viewModel.addItem("Bread")
        runCurrent()
        advanceTimeBy(debounceMs + 100)
        runCurrent()

        coVerify(exactly = 1) {
            sendListUpdateToChatsUseCase(
                "list1",
                "Groceries",
                listOf("chat1", "chat2", "chat3"),
                ListDiff(added = listOf("Bread")),
            )
        }
    }

    @Test
    fun `pending edit is NOT dropped when the screen is left within the debounce window`() = runTest {
        // Documents the bug that was causing "no update at all" when the user
        // navigated back within 10 seconds of editing. When fixed (Step 2 of
        // the plan), onCleared() must flush any pending diff rather than
        // silently cancelling it.
        every { listRepository.observeList("list1") } returns flowOf(sharedList())
        coEvery { listRepository.addItem("list1", "Bread", null, null) } returns Result.success(Unit)

        val viewModel = buildViewModel()
        runCurrent()

        viewModel.addItem("Bread")
        runCurrent()

        // Simulate the user navigating away mid-debounce.
        val onCleared = ListDetailViewModel::class.java.getDeclaredMethod("onCleared").apply {
            isAccessible = true
        }
        onCleared.invoke(viewModel)
        runCurrent()
        advanceTimeBy(debounceMs + 100)
        runCurrent()

        coVerify(exactly = 1) {
            sendListUpdateToChatsUseCase(
                "list1",
                "Groceries",
                listOf("chat1"),
                ListDiff(added = listOf("Bread")),
            )
        }
    }
}
