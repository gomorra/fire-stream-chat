package com.firestream.chat.ui.chat

import com.firestream.chat.domain.model.ChatType
import com.firestream.chat.domain.reminder.DateTimeDetector
import com.firestream.chat.domain.repository.ReminderRepository
import com.firestream.chat.test.MainDispatcherRule
import com.firestream.chat.test.TestData
import com.firestream.chat.test.fakes.FakeMessageRepository
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test

/**
 * Forwarding one message to the chats the picker handed over.
 *
 * The regression this guards is the group case: the picker's predecessor
 * addressed every forward to `participants.first { it != me }`, so a forward
 * into a group named one arbitrary member as the recipient — which a release
 * build reads as "encrypt to that member's Signal session", leaving the rest of
 * the group with an unreadable message. A group must be addressed to nobody.
 */
class ChatMessageActionsForwardTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule(StandardTestDispatcher())

    private val me = "me"
    private val repository = FakeMessageRepository()
    private val reminderRepository = mockk<ReminderRepository>(relaxed = true)
    private val dateTimeDetector = mockk<DateTimeDetector>(relaxed = true) {
        coEvery { detect(any(), any()) } returns null
    }
    private val uiState = MutableStateFlow(
        ChatUiState().let { state ->
            state.copy(
                session = state.session.copy(
                    currentUserId = me,
                    chatParticipants = mapOf("user-2" to TestData.user(uid = "user-2", displayName = "Alice")),
                )
            )
        }
    )

    private val oneToOne = TestData.chat(id = "c1", participants = listOf(me, "user-2"))
    private val group = TestData.chat(
        id = "c2",
        type = ChatType.GROUP,
        name = "Weekend Trip",
        participants = listOf(me, "user-2", "user-3"),
    )

    private fun actions() = ChatMessageActions(
        chatId = "chat1",
        recipientId = "user-2",
        messageRepository = repository,
        reminderRepository = reminderRepository,
        dateTimeDetector = dateTimeDetector,
        _uiState = uiState,
        scope = TestScope(mainDispatcherRule.testDispatcher),
    )

    @Test
    fun `a group forward is addressed to nobody, a 1-1 to the other participant`() = runTest {
        actions().forwardMessage(TestData.message(), listOf(oneToOne, group))
        advanceUntilIdle()

        assertEquals(listOf("c1" to "user-2", "c2" to ""), repository.forwardedTargets)
    }

    @Test
    fun `forwarding to one chat confirms with its name`() = runTest {
        var destination: String? = null
        actions().forwardMessage(TestData.message(), listOf(oneToOne)) { destination = it }
        advanceUntilIdle()

        assertEquals("Alice", destination)
    }

    @Test
    fun `forwarding to several chats confirms with a count`() = runTest {
        var destination: String? = null
        actions().forwardMessage(TestData.message(), listOf(oneToOne, group)) { destination = it }
        advanceUntilIdle()

        assertEquals("2 chats", destination)
    }

    @Test
    fun `a forward that failed everywhere reports the error instead of a confirmation`() = runTest {
        repository.nextFailure = IllegalStateException("offline")
        var destination: String? = null
        actions().forwardMessage(TestData.message(), listOf(oneToOne)) { destination = it }
        advanceUntilIdle()

        assertNull(destination)
        assertEquals("offline", uiState.value.session.error?.message)
    }

    @Test
    fun `no chats picked is not a send`() = runTest {
        actions().forwardMessage(TestData.message(), emptyList())
        advanceUntilIdle()

        assertEquals(emptyList<Pair<String, String>>(), repository.forwardedTargets)
    }
}
