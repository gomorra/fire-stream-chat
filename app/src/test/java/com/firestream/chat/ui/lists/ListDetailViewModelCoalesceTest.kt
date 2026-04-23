package com.firestream.chat.ui.lists

import androidx.lifecycle.SavedStateHandle
import com.firestream.chat.data.local.PreferencesDataStore
import com.firestream.chat.domain.model.ListData
import com.firestream.chat.domain.model.ListDiff
import com.firestream.chat.domain.repository.AuthRepository
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
 * Locks down the sender-side "list edit → chat bubble" path: bubble fires on the
 * leading edge, is suppressed for COOLDOWN_MS, and the cooldown resets on every
 * new edit.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ListDetailViewModelCoalesceTest {

    // Must stay in sync with `COOLDOWN_MS` in ListDetailViewModel.
    private val cooldownMs = 10_000L

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

    private fun buildViewModel() = ListDetailViewModel(
        savedStateHandle = SavedStateHandle(mapOf("listId" to "list1")),
        sendListUpdateToChatsUseCase = sendListUpdateToChatsUseCase,
        chatRepository = chatRepository,
        listRepository = listRepository,
        authRepository = authRepository,
        userRepository = userRepository,
        preferencesDataStore = mockk<PreferencesDataStore>(relaxed = true),
        applicationScope = TestScope(testDispatcher),
    )

    private fun sharedList(sharedChatIds: List<String> = listOf("chat1")) = ListData(
        id = "list1",
        title = "Groceries",
        sharedChatIds = sharedChatIds,
    )

    @Test
    fun `adding an item sends the list update immediately on the leading edge`() = runTest {
        every { listRepository.observeList("list1") } returns flowOf(sharedList())
        coEvery { listRepository.addItem("list1", any(), "Bread", null, null) } returns Result.success(Unit)

        val viewModel = buildViewModel()
        runCurrent()

        viewModel.addItem("Bread")
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

    @Test
    fun `rapid edits within the cooldown send exactly one bubble`() = runTest {
        every { listRepository.observeList("list1") } returns flowOf(sharedList())
        coEvery { listRepository.addItem("list1", any(), any(), null, null) } returns Result.success(Unit)

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

        // Only the first edit fires a bubble — the other two are suppressed.
        coVerify(exactly = 1) {
            sendListUpdateToChatsUseCase("list1", "Groceries", listOf("chat1"), ListDiff(added = listOf("Bread")))
        }
        coVerify(exactly = 0) {
            sendListUpdateToChatsUseCase(any(), any(), any(), ListDiff(added = listOf("Milk")))
        }
        coVerify(exactly = 0) {
            sendListUpdateToChatsUseCase(any(), any(), any(), ListDiff(added = listOf("Eggs")))
        }
    }

    @Test
    fun `cooldown resets on each edit — a new bubble only fires after a full quiet window`() = runTest {
        every { listRepository.observeList("list1") } returns flowOf(sharedList())
        coEvery { listRepository.addItem("list1", any(), any(), null, null) } returns Result.success(Unit)

        val viewModel = buildViewModel()
        runCurrent()

        // First edit — fires the leading-edge bubble.
        viewModel.addItem("Bread")
        runCurrent()

        // Keep editing just before the cooldown would expire; each edit pushes it back.
        advanceTimeBy(cooldownMs - 100)
        viewModel.addItem("Milk")
        runCurrent()
        advanceTimeBy(cooldownMs - 100)
        viewModel.addItem("Eggs")
        runCurrent()

        // Still only the first bubble — the window kept resetting.
        coVerify(exactly = 1) { sendListUpdateToChatsUseCase(any(), any(), any(), any()) }

        // Now stay quiet past the reset window. The next edit fires a fresh bubble.
        advanceTimeBy(cooldownMs + 100)
        runCurrent()
        viewModel.addItem("Butter")
        runCurrent()

        coVerify(exactly = 2) { sendListUpdateToChatsUseCase(any(), any(), any(), any()) }
        coVerify(exactly = 1) {
            sendListUpdateToChatsUseCase("list1", "Groceries", listOf("chat1"), ListDiff(added = listOf("Butter")))
        }
    }

    @Test
    fun `fanout hits every shared chat on the leading-edge send`() = runTest {
        every { listRepository.observeList("list1") } returns
            flowOf(sharedList(sharedChatIds = listOf("chat1", "chat2", "chat3")))
        coEvery { listRepository.addItem("list1", any(), "Bread", null, null) } returns Result.success(Unit)

        val viewModel = buildViewModel()
        runCurrent()

        viewModel.addItem("Bread")
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
}
