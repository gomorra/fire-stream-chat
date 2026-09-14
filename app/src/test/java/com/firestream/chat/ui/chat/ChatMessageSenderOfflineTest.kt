package com.firestream.chat.ui.chat

import com.firestream.chat.test.fakes.FakeChatRepository
import com.firestream.chat.test.fakes.FakeMessageRepository
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Regression for the "text messages vanish without a network" bug (2026-09-14).
 *
 * A text send used to await the typing-indicator write *before* handing the
 * message to the repository. That write is a Firestore update awaited until the
 * server acks, so with no connection it never returned: the optimistic bubble
 * never appeared (a photo, whose path has no such write, showed its clock at
 * once), and swiping the app away cancelled the coroutine before the message
 * was ever written to Room — lost for good. The send must reach the repository
 * without waiting on any remote write.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ChatMessageSenderOfflineTest {

    private val chatRepository = FakeChatRepository()
    private val messageRepository = FakeMessageRepository()
    private val uiState = MutableStateFlow(ChatUiState())

    @Test
    fun `text send reaches the repository while the typing write never completes`() = runTest {
        chatRepository.typingGate = CompletableDeferred() // never completed: offline
        val sender = ChatMessageSender("chat-1", "peer-1", chatRepository, messageRepository, uiState, backgroundScope)

        sender.sendMessage("hello")
        runCurrent()

        assertEquals("hello", messageRepository.lastSentMessage?.content)
        assertEquals("peer-1", messageRepository.lastSentRecipientId)
    }

    @Test
    fun `three sends in a row all reach the repository while offline`() = runTest {
        chatRepository.typingGate = CompletableDeferred()
        val sender = ChatMessageSender("chat-1", "peer-1", chatRepository, messageRepository, uiState, backgroundScope)

        sender.sendMessage("one")
        sender.sendMessage("two")
        sender.sendMessage("three")
        runCurrent()

        assertEquals(listOf("one", "two", "three"), messageRepository.getMessages("chat-1").first().map { it.content })
    }

    @Test
    fun `typing-off is still requested alongside the send`() = runTest {
        chatRepository.typingGate = CompletableDeferred()
        val sender = ChatMessageSender("chat-1", "peer-1", chatRepository, messageRepository, uiState, backgroundScope)

        sender.sendMessage("hello")
        runCurrent()

        assertTrue(chatRepository.typingCalls.contains("chat-1" to false))
    }
}
