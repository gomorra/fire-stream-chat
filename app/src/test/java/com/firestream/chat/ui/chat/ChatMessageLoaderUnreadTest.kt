package com.firestream.chat.ui.chat

import android.content.Context
import com.firestream.chat.data.remote.LinkPreviewSource
import com.firestream.chat.domain.repository.ListRepository
import com.firestream.chat.test.MainDispatcherRule
import com.firestream.chat.test.fakes.FakeChatRepository
import com.firestream.chat.test.fakes.FakeMessageRepository
import io.mockk.mockk
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

// StandardTestDispatcher (not Unconfined) so advanceTimeBy controls the 2s delayed-reset window.
@OptIn(ExperimentalCoroutinesApi::class)
class ChatMessageLoaderUnreadTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule(StandardTestDispatcher())

    private val chatRepository = FakeChatRepository()
    private val messageRepository = FakeMessageRepository()
    private val listRepository = mockk<ListRepository>(relaxed = true)
    private val linkPreviewSource = mockk<LinkPreviewSource>(relaxed = true)
    private val context = mockk<Context>(relaxed = true)

    private val uiState = MutableStateFlow(ChatUiState(currentUserId = "uid1"))

    private fun loader(scope: CoroutineScope): ChatMessageLoader = ChatMessageLoader(
        chatId = "chat1",
        listRepository = listRepository,
        linkPreviewSource = linkPreviewSource,
        chatRepository = chatRepository,
        messageRepository = messageRepository,
        context = context,
        _uiState = uiState,
        scope = scope,
    )

    @Test
    fun `setScreenVisible true triggers immediate unread reset`() = runTest {
        loader(this).setScreenVisible(true)

        advanceTimeBy(100) // past immediate launch, short of the 2s delayed reset
        assertEquals(listOf("chat1"), chatRepository.resetUnreadCalls)
    }

    @Test
    fun `delayed 2s reset fires after initial reset (Cloud Function race)`() = runTest {
        loader(this).setScreenVisible(true)

        advanceTimeBy(500)
        assertEquals(1, chatRepository.resetUnreadCalls.size)

        advanceTimeBy(1600) // total elapsed ≥ 2000ms → delayed reset fires
        assertEquals(2, chatRepository.resetUnreadCalls.size)
        assertEquals("chat1", chatRepository.resetUnreadCalls.last())
    }

    @Test
    fun `setScreenVisible false cancels pending delayed reset`() = runTest {
        val l = loader(this)
        l.setScreenVisible(true)
        advanceTimeBy(500) // delayed reset still queued
        assertEquals(1, chatRepository.resetUnreadCalls.size)

        l.setScreenVisible(false) // cancels pendingUnreadResetJob

        advanceTimeBy(5000) // well past the 2s mark
        assertEquals(1, chatRepository.resetUnreadCalls.size)
    }

    @Test
    fun `setAtBottom true triggers reset when screen is visible`() = runTest {
        val l = loader(this)
        l.setScreenVisible(true)
        advanceTimeBy(100)
        val callsAfterShow = chatRepository.resetUnreadCalls.size
        assertEquals(1, callsAfterShow)

        l.setAtBottom(false)
        l.setAtBottom(true)
        advanceTimeBy(100)

        assertTrue(
            "expected another reset after returning to bottom, got $callsAfterShow → ${chatRepository.resetUnreadCalls.size}",
            chatRepository.resetUnreadCalls.size > callsAfterShow,
        )
    }

    @Test
    fun `no reset when screen is not visible`() = runTest {
        loader(this).setAtBottom(true) // toggles atBottom but screenVisible == false

        advanceUntilIdle()
        assertTrue(chatRepository.resetUnreadCalls.isEmpty())
    }

    @Test
    fun `no reset when not at bottom`() = runTest {
        val l = loader(this)
        l.setAtBottom(false)

        l.setScreenVisible(true)

        advanceUntilIdle()
        assertTrue(chatRepository.resetUnreadCalls.isEmpty())
    }

    @Test
    fun `delayed reset is skipped if screen becomes invisible before it fires`() = runTest {
        val l = loader(this)
        l.setScreenVisible(true)
        advanceTimeBy(1000)
        l.setScreenVisible(false)
        advanceTimeBy(2000)

        // Only the initial reset; setScreenVisible(false) cancels the
        // pendingUnreadResetJob before its 2s body runs.
        assertEquals(1, chatRepository.resetUnreadCalls.size)
    }
}
