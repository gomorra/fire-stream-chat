package com.firestream.chat.ui.chat

import android.content.Context
import com.firestream.chat.data.remote.LinkPreviewSource
import com.firestream.chat.domain.model.Message
import com.firestream.chat.domain.repository.ListRepository
import com.firestream.chat.domain.repository.ReminderRepository
import com.firestream.chat.test.MainDispatcherRule
import com.firestream.chat.test.fakes.FakeChatRepository
import com.firestream.chat.test.fakes.FakeMessageRepository
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test

/**
 * Covers the *delivery* of in-chat reaction cues: that a reaction another user adds
 * to one of my messages lands on `ChatUiState.messages.newOwnReaction`, on the same
 * slice as the message list, so ChatScreen can flash the bubble or raise the
 * jump-to-reaction FAB.
 *
 * The diff itself is covered by [DetectNewOwnReactionsTest]; that logic was already
 * correct and unit-tested while the feature was still dead on-device twice over
 * (1.18.0 shipped it over a loader SharedFlow, 1.18.3 over a ChatScreen snapshotFlow
 * baseline — neither reached the screen). Delivery is the part that kept breaking, so
 * it gets its own test.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ChatMessageLoaderReactionCueTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule(StandardTestDispatcher())

    private val me = "uid1"
    private val other = "uid2"

    private val chatRepository = FakeChatRepository()
    private val messageRepository = FakeMessageRepository()
    private val listRepository = mockk<ListRepository>(relaxed = true)
    private val linkPreviewSource = mockk<LinkPreviewSource>(relaxed = true)
    private val reminderRepository = mockk<ReminderRepository>(relaxed = true) {
        // Explicit stub: a relaxed-mock Flow never emits, which would hang the
        // loader's combine() forever waiting for a first value from this side.
        every { observePendingIdsForChat(any()) } returns flowOf(emptySet())
    }
    private val context = mockk<Context>(relaxed = true)

    private val uiState = MutableStateFlow(ChatUiState(session = SessionState(currentUserId = me)))

    private val loaderScopes = mutableListOf<CoroutineScope>()

    @After
    fun tearDown() {
        loaderScopes.forEach { it.cancel() }
    }

    /**
     * Starts a loader on a scope that is deliberately neither the test's own scope
     * nor `backgroundScope`: `this` would leave `runTest` waiting forever on the
     * never-completing message collector, and emissions to a `backgroundScope`
     * collector are not delivered by `advanceUntilIdle()`. A root scope sharing the
     * test dispatcher gets both — driven by `advanceUntilIdle()`, cancelled in
     * [tearDown].
     */
    private fun TestScope.startLoader(): ChatMessageLoader {
        val scope = CoroutineScope(coroutineContext + SupervisorJob())
        loaderScopes += scope
        return loader(scope).also { it.start() }
    }

    private fun loader(scope: CoroutineScope): ChatMessageLoader = ChatMessageLoader(
        chatId = "chat1",
        listRepository = listRepository,
        linkPreviewSource = linkPreviewSource,
        chatRepository = chatRepository,
        messageRepository = messageRepository,
        reminderRepository = reminderRepository,
        context = context,
        _uiState = uiState,
        scope = scope,
    )

    private fun msg(id: String, senderId: String, reactions: Map<String, String> = emptyMap()) =
        Message(id = id, chatId = "chat1", senderId = senderId, reactions = reactions)

    private val cue get() = uiState.value.messages.newOwnReaction

    @Test
    fun `reaction from another user on my message lands on the messages slice`() = runTest {
        startLoader()
        messageRepository.emit("chat1", listOf(msg("m1", me)))
        advanceUntilIdle()
        assertNull("baseline emission must not fire a cue", cue)

        messageRepository.emit("chat1", listOf(msg("m1", me, mapOf(other to "❤️"))))
        advanceUntilIdle()

        assertEquals(ReactionAlert("m1", "❤️"), cue)
    }

    @Test
    fun `cue arrives on the same state as the list it was diffed from`() = runTest {
        // The screen resolves the cue's messageId against uiState.messages.messages;
        // if the two ever land on separate emissions the lookup misses and the cue
        // is silently dropped.
        startLoader()
        messageRepository.emit("chat1", listOf(msg("m1", me)))
        advanceUntilIdle()

        messageRepository.emit("chat1", listOf(msg("m1", me, mapOf(other to "🔥"))))
        advanceUntilIdle()

        val slice = uiState.value.messages
        assertEquals(ReactionAlert("m1", "🔥"), slice.newOwnReaction)
        assertEquals(mapOf(other to "🔥"), slice.messages.single { it.id == "m1" }.reactions)
    }

    @Test
    fun `pre-existing reactions on chat open fire no cue`() = runTest {
        startLoader()

        // First real emission already carries a reaction — the chat was opened after
        // someone reacted, which FCM already notified about.
        messageRepository.emit("chat1", listOf(msg("m1", me, mapOf(other to "👍"))))
        advanceUntilIdle()

        assertNull(cue)
    }

    @Test
    fun `my own reaction on my own message fires no cue`() = runTest {
        startLoader()
        messageRepository.emit("chat1", listOf(msg("m1", me)))
        advanceUntilIdle()

        messageRepository.emit("chat1", listOf(msg("m1", me, mapOf(me to "👍"))))
        advanceUntilIdle()

        assertNull(cue)
    }

    @Test
    fun `reaction on someone else's message fires no cue`() = runTest {
        startLoader()
        messageRepository.emit("chat1", listOf(msg("m1", other)))
        advanceUntilIdle()

        messageRepository.emit("chat1", listOf(msg("m1", other, mapOf(me to "👍"))))
        advanceUntilIdle()

        assertNull(cue)
    }

    @Test
    fun `unconsumed cue survives later emissions that carry no new reaction`() = runTest {
        // ChatScreen acts on the cue a frame later, and read-receipt / delivery-status
        // writes re-emit the list in between. Clobbering the cue on those emissions is
        // exactly how a reaction cue gets lost between the loader and the screen.
        startLoader()
        messageRepository.emit("chat1", listOf(msg("m1", me)))
        advanceUntilIdle()

        messageRepository.emit("chat1", listOf(msg("m1", me, mapOf(other to "😂"))))
        advanceUntilIdle()
        assertEquals(ReactionAlert("m1", "😂"), cue)

        // An unrelated re-emission (e.g. status flip to READ) with the same reactions.
        messageRepository.emit(
            "chat1",
            listOf(msg("m1", me, mapOf(other to "😂")), msg("m2", other)),
        )
        advanceUntilIdle()

        assertEquals("cue must survive until the screen consumes it", ReactionAlert("m1", "😂"), cue)
    }

    @Test
    fun `consumeReactionCue clears the cue so the next reaction fires a fresh one`() = runTest {
        val l = startLoader()
        messageRepository.emit("chat1", listOf(msg("m1", me)))
        advanceUntilIdle()
        messageRepository.emit("chat1", listOf(msg("m1", me, mapOf(other to "❤️"))))
        advanceUntilIdle()

        l.consumeReactionCue()
        assertNull(cue)

        messageRepository.emit("chat1", listOf(msg("m1", me, mapOf(other to "🎉"))))
        advanceUntilIdle()

        assertEquals(ReactionAlert("m1", "🎉"), cue)
    }
}
