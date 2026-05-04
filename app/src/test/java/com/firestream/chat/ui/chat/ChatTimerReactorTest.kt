package com.firestream.chat.ui.chat

import com.firestream.chat.data.timer.ScheduleResult
import com.firestream.chat.data.timer.TimerAlarmScheduler
import com.firestream.chat.domain.model.Message
import com.firestream.chat.domain.model.MessageType
import com.firestream.chat.domain.model.TimerState
import com.firestream.chat.test.MainDispatcherRule
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ChatTimerReactorTest {

    @get:Rule val mainDispatcherRule = MainDispatcherRule()

    private val now = 1_000_000L
    private val futureFireAt = 1_030_000L  // now + 30s
    private val pastFireAt = 999_000L      // 1s before now

    private val scheduler: TimerAlarmScheduler = mockk(relaxed = true) {
        every { schedule(any(), any(), any(), any(), any()) } returns ScheduleResult.EXACT
    }

    private val state = MutableStateFlow(ChatUiState())
    private val results = mutableListOf<ScheduleResult>()

    private fun reactor(scope: kotlinx.coroutines.CoroutineScope) = ChatTimerReactor(
        chatId = "chat-1",
        recipientId = "user-recipient",
        scheduler = scheduler,
        _uiState = state,
        scope = scope,
        onScheduleResult = { results.add(it) },
        nowMs = { now },
    )

    private fun timer(
        id: String,
        startedAt: Long? = now,
        duration: Long? = 30_000L,
        timerState: TimerState? = TimerState.RUNNING,
        caption: String = "",
    ) = Message(
        id = id,
        chatId = "chat-1",
        senderId = "u1",
        content = caption,
        type = MessageType.TIMER,
        timerStartedAtMs = startedAt,
        timerDurationMs = duration,
        timerState = timerState,
    )

    private fun pushMessages(vararg messages: Message) {
        state.update { it.copy(messages = it.messages.copy(messages = messages.toList())) }
    }

    @Test
    fun `schedules running timer when message arrives`() = runTest(UnconfinedTestDispatcher()) {
        reactor(backgroundScope).start()

        pushMessages(timer(id = "t1", startedAt = now, duration = 30_000L, caption = "Tea"))

        verify {
            scheduler.schedule(
                messageId = "t1",
                fireAtMs = futureFireAt,
                caption = "Tea",
                chatId = "chat-1",
                otherUserId = "user-recipient",
            )
        }
        assertEquals(listOf(ScheduleResult.EXACT), results)
    }

    @Test
    fun `cancels timer when state flips to CANCELLED`() = runTest(UnconfinedTestDispatcher()) {
        reactor(backgroundScope).start()
        pushMessages(timer(id = "t1"))

        pushMessages(timer(id = "t1", timerState = TimerState.CANCELLED))

        verify { scheduler.cancel("t1") }
    }

    @Test
    fun `cancels timer when state flips to COMPLETED`() = runTest(UnconfinedTestDispatcher()) {
        reactor(backgroundScope).start()
        pushMessages(timer(id = "t1"))

        pushMessages(timer(id = "t1", timerState = TimerState.COMPLETED))

        verify { scheduler.cancel("t1") }
    }

    @Test
    fun `does not schedule past-fire-time timer`() = runTest(UnconfinedTestDispatcher()) {
        reactor(backgroundScope).start()

        pushMessages(timer(id = "stale", startedAt = pastFireAt - 30_000L, duration = 30_000L))

        verify(exactly = 0) {
            scheduler.schedule(any(), any(), any(), any(), any())
        }
    }

    @Test
    fun `passes empty recipient id as null`() = runTest(UnconfinedTestDispatcher()) {
        val reactor = ChatTimerReactor(
            chatId = "chat-1",
            recipientId = "",
            scheduler = scheduler,
            _uiState = state,
            scope = backgroundScope,
            onScheduleResult = {},
            nowMs = { now },
        )
        reactor.start()

        pushMessages(timer(id = "t1"))

        verify { scheduler.schedule(any(), any(), any(), any(), otherUserId = null) }
    }

    @Test
    fun `INEXACT_FALLBACK result is delivered to onScheduleResult callback`() = runTest(UnconfinedTestDispatcher()) {
        every { scheduler.schedule(any(), any(), any(), any(), any()) } returns ScheduleResult.INEXACT_FALLBACK

        reactor(backgroundScope).start()
        pushMessages(timer(id = "t1"))

        assertEquals(listOf(ScheduleResult.INEXACT_FALLBACK), results)
    }

    @Test
    fun `ignores non-timer messages`() = runTest(UnconfinedTestDispatcher()) {
        reactor(backgroundScope).start()

        pushMessages(
            Message(id = "x", chatId = "chat-1", senderId = "u1", content = "hi", type = MessageType.TEXT)
        )

        verify(exactly = 0) {
            scheduler.schedule(any(), any(), any(), any(), any())
        }
        verify(exactly = 0) { scheduler.cancel(any()) }
    }

    @Test
    fun `does not re-schedule when unrelated message changes`() = runTest(UnconfinedTestDispatcher()) {
        reactor(backgroundScope).start()
        val running = timer(id = "t1", caption = "Tea")
        pushMessages(running)

        // Add a TEXT message — TIMER fingerprint is unchanged.
        pushMessages(
            running,
            Message(id = "x", chatId = "chat-1", senderId = "u1", content = "hi", type = MessageType.TEXT),
        )

        // schedule should have been called exactly once for the original push.
        coVerify(exactly = 1) {
            scheduler.schedule(eq("t1"), any(), any(), any(), any())
        }
    }

    @Test
    fun `re-schedules when timer fire time is updated`() = runTest(UnconfinedTestDispatcher()) {
        reactor(backgroundScope).start()

        pushMessages(timer(id = "t1", startedAt = now, duration = 30_000L))
        pushMessages(timer(id = "t1", startedAt = now + 5_000L, duration = 30_000L))

        coVerify(exactly = 1) { scheduler.schedule("t1", futureFireAt, any(), any(), any()) }
        coVerify(exactly = 1) { scheduler.schedule("t1", futureFireAt + 5_000L, any(), any(), any()) }
    }

    @Test
    fun `schedules multiple concurrent timers in same chat`() = runTest(UnconfinedTestDispatcher()) {
        reactor(backgroundScope).start()

        pushMessages(
            timer(id = "t1", caption = "Tea"),
            timer(id = "t2", duration = 60_000L, caption = "Pasta"),
        )

        coVerify(exactly = 1) { scheduler.schedule("t1", futureFireAt, "Tea", any(), any()) }
        coVerify(exactly = 1) { scheduler.schedule("t2", now + 60_000L, "Pasta", any(), any()) }
        assertTrue(results.all { it == ScheduleResult.EXACT })
        assertEquals(2, results.size)
    }
}
