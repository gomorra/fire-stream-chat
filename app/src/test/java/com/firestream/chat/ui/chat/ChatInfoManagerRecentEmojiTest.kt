package com.firestream.chat.ui.chat

import com.firestream.chat.data.local.PreferencesDataStore
import com.firestream.chat.domain.repository.ListRepository
import com.firestream.chat.domain.usecase.chat.CheckGroupPermissionUseCase
import com.firestream.chat.test.MainDispatcherRule
import com.firestream.chat.test.fakes.FakeChatRepository
import com.firestream.chat.test.fakes.FakeUserRepository
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

/**
 * The recent-emoji order is published live from DataStore — there is no debounce in
 * the manager. Order-freezing while the picker is open is a presentation concern owned
 * by [EmojiHandlerPanel] (it snapshots the list once per open session), so the manager
 * simply forwards every distinct emission immediately.
 */
class ChatInfoManagerRecentEmojiTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule(StandardTestDispatcher())

    private val recentsFlow = MutableSharedFlow<List<String>>(replay = 1)

    private val preferencesDataStore = mockk<PreferencesDataStore>(relaxed = true).also {
        every { it.recentEmojisFlow } returns recentsFlow as Flow<List<String>>
    }

    private val uiState = MutableStateFlow(ChatUiState(session = SessionState(currentUserId = "uid1")))

    private fun manager() = ChatInfoManager(
        chatId = "chat1",
        recipientId = "",
        chatRepository = FakeChatRepository(),
        listRepository = mockk<ListRepository>(relaxed = true),
        userRepository = FakeUserRepository(),
        preferencesDataStore = preferencesDataStore,
        checkGroupPermissionUseCase = mockk<CheckGroupPermissionUseCase>(relaxed = true),
        _uiState = uiState,
        scope = TestScope(mainDispatcherRule.testDispatcher),
    )

    @Test
    fun `first emission updates recentEmojis immediately`() = runTest(mainDispatcherRule.testDispatcher) {
        val mgr = manager()
        mgr.start()

        recentsFlow.emit(listOf("😀", "❤️"))
        advanceUntilIdle()

        assertEquals(listOf("😀", "❤️"), uiState.value.overlays.recentEmojis)
    }

    @Test
    fun `subsequent emission updates immediately without debounce`() = runTest(mainDispatcherRule.testDispatcher) {
        val mgr = manager()
        mgr.start()

        recentsFlow.emit(listOf("😀"))
        advanceUntilIdle()

        recentsFlow.emit(listOf("❤️", "😀"))
        advanceUntilIdle()

        assertEquals(listOf("❤️", "😀"), uiState.value.overlays.recentEmojis)
    }

    @Test
    fun `each rapid emission is applied in order`() = runTest(mainDispatcherRule.testDispatcher) {
        val mgr = manager()
        mgr.start()

        recentsFlow.emit(listOf("😀"))
        advanceUntilIdle()
        assertEquals(listOf("😀"), uiState.value.overlays.recentEmojis)

        recentsFlow.emit(listOf("😂", "😀"))
        advanceUntilIdle()
        assertEquals(listOf("😂", "😀"), uiState.value.overlays.recentEmojis)

        recentsFlow.emit(listOf("❤️", "😂", "😀"))
        advanceUntilIdle()
        assertEquals(listOf("❤️", "😂", "😀"), uiState.value.overlays.recentEmojis)
    }
}
