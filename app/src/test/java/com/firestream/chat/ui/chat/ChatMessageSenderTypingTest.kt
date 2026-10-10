package com.firestream.chat.ui.chat

import com.firestream.chat.test.fakes.FakeChatRepository
import com.firestream.chat.test.fakes.FakeMessageRepository
import com.firestream.chat.test.fakes.emptyStickerRepository
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The typing indicator costs one backend write per [TYPING_REFRESH_MS] of
 * typing and one when it ends. It used to cost one write per keystroke, each
 * of which every member's chat listener paid a read for.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ChatMessageSenderTypingTest {

    private val chatRepository = FakeChatRepository()
    private val on = "chat-1" to true
    private val off = "chat-1" to false

    private fun TestScope.sender() = ChatMessageSender(
        "chat-1", chatRepository, FakeMessageRepository(), emptyStickerRepository(),
        MutableStateFlow(ChatUiState()), backgroundScope
    )

    @Test
    fun `a burst of keystrokes writes typing-on once`() = runTest {
        val sender = sender()

        "hello there".indices.forEach { i ->
            sender.onTyping("hello there".take(i + 1))
            advanceTimeBy(100)
        }
        runCurrent()

        assertEquals(listOf(on), chatRepository.typingCalls)
    }

    @Test
    fun `continuous typing re-announces once per refresh window`() = runTest {
        val sender = sender()

        // A keystroke every 500 ms for just under three refresh windows.
        repeat(17) {
            sender.onTyping("x")
            advanceTimeBy(500)
        }
        runCurrent()

        assertEquals(listOf(on, on, on), chatRepository.typingCalls)
    }

    @Test
    fun `typing-off follows the idle timeout, and the next keystroke announces again`() = runTest {
        val sender = sender()

        sender.onTyping("a")
        advanceTimeBy(TYPING_IDLE_MS + 1)
        assertEquals(listOf(on, off), chatRepository.typingCalls)

        sender.onTyping("ab")
        runCurrent()
        assertEquals(listOf(on, off, on), chatRepository.typingCalls)
    }

    @Test
    fun `clearing the field writes typing-off once, and only after a typing-on`() = runTest {
        val sender = sender()

        sender.onTyping("")
        runCurrent()
        assertEquals(emptyList<Pair<String, Boolean>>(), chatRepository.typingCalls)

        sender.onTyping("a")
        sender.onTyping("")
        sender.onTyping("")
        advanceTimeBy(TYPING_IDLE_MS + 1)
        assertEquals(listOf(on, off), chatRepository.typingCalls)
    }

    @Test
    fun `a keystroke right after clearing the field announces again`() = runTest {
        val sender = sender()

        sender.onTyping("a")
        sender.onTyping("")
        sender.onTyping("b")
        runCurrent()

        assertEquals(listOf(on, off, on), chatRepository.typingCalls)
    }

    @Test
    fun `a send writes typing-off once, even with the composer cleared after it`() = runTest {
        val sender = sender()

        sender.onTyping("hello")
        sender.sendMessage("hello")
        sender.onTyping("")
        advanceTimeBy(TYPING_IDLE_MS + 1)

        assertEquals(listOf(on, off), chatRepository.typingCalls)
    }

    @Test
    fun `a send with no typing announced writes nothing to the typing indicator`() = runTest {
        val sender = sender()

        sender.sendMessage("pasted and sent")
        runCurrent()

        assertEquals(emptyList<Pair<String, Boolean>>(), chatRepository.typingCalls)
    }
}
