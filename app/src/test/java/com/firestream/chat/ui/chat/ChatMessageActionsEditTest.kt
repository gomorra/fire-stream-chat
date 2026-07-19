package com.firestream.chat.ui.chat

import com.firestream.chat.domain.model.Message
import com.firestream.chat.domain.repository.ReminderRepository
import com.firestream.chat.test.MainDispatcherRule
import com.firestream.chat.test.fakes.FakeMessageRepository
import io.mockk.mockk
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test

/**
 * Regression test: editing a message must persist the composer's emoji-size map,
 * not just the text. Sizes are replaced wholesale on every edit — the composer
 * seeds them from the message when the edit starts, so whatever it holds at
 * confirm time is the complete new state (an empty map clears all overrides).
 */
class ChatMessageActionsEditTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule(StandardTestDispatcher())

    private val chatId = "chat1"
    private val repository = FakeMessageRepository()
    private val reminderRepository = mockk<ReminderRepository>(relaxed = true)
    private val uiState = MutableStateFlow(ChatUiState())

    private fun actions() = ChatMessageActions(
        chatId = chatId,
        recipientId = "recipient1",
        messageRepository = repository,
        reminderRepository = reminderRepository,
        _uiState = uiState,
        scope = TestScope(mainDispatcherRule.testDispatcher),
    )

    private fun seedMessage(emojiSizes: Map<Int, Float>): Message {
        val message = Message(
            id = "msg1",
            chatId = chatId,
            senderId = "me",
            content = "Hi 😀",
            emojiSizes = emojiSizes,
        )
        repository.emit(chatId, listOf(message))
        uiState.value = uiState.value.copy(
            composer = uiState.value.composer.copy(editingMessage = message)
        )
        return message
    }

    @Test
    fun `confirmEdit persists changed emoji sizes`() = runTest(mainDispatcherRule.testDispatcher) {
        seedMessage(emojiSizes = mapOf(3 to 1.5f))

        actions().confirmEdit("Hi 😀", emojiSizes = mapOf(3 to 3.0f))
        advanceUntilIdle()

        val edited = repository.getMessages(chatId).first().single()
        assertEquals(mapOf(3 to 3.0f), edited.emojiSizes)
        assertNull(uiState.value.composer.editingMessage)
    }

    @Test
    fun `confirmEdit adds a size to a previously standard-size emoji`() = runTest(mainDispatcherRule.testDispatcher) {
        seedMessage(emojiSizes = emptyMap())

        actions().confirmEdit("Hi 😀", emojiSizes = mapOf(3 to 2.0f))
        advanceUntilIdle()

        val edited = repository.getMessages(chatId).first().single()
        assertEquals(mapOf(3 to 2.0f), edited.emojiSizes)
    }

    @Test
    fun `confirmEdit with empty map clears previous size overrides`() = runTest(mainDispatcherRule.testDispatcher) {
        seedMessage(emojiSizes = mapOf(3 to 2.0f))

        actions().confirmEdit("Hi 😀", emojiSizes = emptyMap())
        advanceUntilIdle()

        val edited = repository.getMessages(chatId).first().single()
        assertEquals(emptyMap<Int, Float>(), edited.emojiSizes)
    }
}
