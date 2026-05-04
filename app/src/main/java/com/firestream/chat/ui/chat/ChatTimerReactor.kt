// region: AGENT-NOTE
// Responsibility: react to TIMER message state changes by scheduling or cancelling
//   AlarmManager entries via TimerAlarmScheduler. This is the bidirectional wire
//   that makes both sender and recipient ring at the server-stamped fire time.
//   Listens to ChatUiState.messages and distincts on (id, state, startedAt+duration)
//   so non-timer churn doesn't churn AlarmManager.
// Owns: nothing in ChatUiState — pure reactor. The schedule/cancel calls into
//   TimerAlarmScheduler are idempotent (FLAG_UPDATE_CURRENT for schedule,
//   PendingIntent.cancel for cancel) so re-emitting the same snapshot is safe.
// Collaborators: TimerAlarmScheduler (data/timer); the onScheduleResult lambda
//   bridges INEXACT_FALLBACK back to ChatViewModel which surfaces the in-app
//   banner via ChatCommandsManager.setExactAlarmBannerVisible.
// Don't put here: scheduling business logic (lives in TimerAlarmScheduler);
//   Room writes for COMPLETED/CANCELLED (lives in MessageRepositoryImpl, invoked
//   by TimerAlarmReceiver on alarm fire and by ChatViewModel.cancelTimer).
//   Per slice-ownership pattern this reactor never mutates ChatUiState.
// endregion

package com.firestream.chat.ui.chat

import com.firestream.chat.data.timer.ScheduleResult
import com.firestream.chat.data.timer.TimerAlarmScheduler
import com.firestream.chat.domain.model.Message
import com.firestream.chat.domain.model.MessageType
import com.firestream.chat.domain.model.TimerState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

internal class ChatTimerReactor(
    private val chatId: String,
    private val recipientId: String,
    private val scheduler: TimerAlarmScheduler,
    private val _uiState: MutableStateFlow<ChatUiState>,
    private val scope: CoroutineScope,
    private val onScheduleResult: (ScheduleResult) -> Unit,
    private val nowMs: () -> Long = { System.currentTimeMillis() },
) {

    // Last reacted (state, fireAtMs) per messageId. Diffing against this on each
    // emission keeps AlarmManager IPCs O(changed-timers) rather than O(timers):
    // a chat with 5 RUNNING timers used to issue 5 schedule() calls every time
    // any one changed.
    private val lastReacted = mutableMapOf<String, TimerSnapshot>()

    fun start() {
        scope.launch {
            _uiState
                .map { it.messages.messages.filter { msg -> msg.type == MessageType.TIMER } }
                .collect { timers -> reconcile(timers) }
        }
    }

    private fun reconcile(timers: List<Message>) {
        val seen = HashSet<String>(timers.size)
        for (msg in timers) {
            seen.add(msg.id)
            val current = TimerSnapshot.of(msg)
            if (lastReacted[msg.id] == current) continue
            lastReacted[msg.id] = current
            reactTo(msg)
        }
        // Messages that disappeared (delete-for-everyone, chat reload). Cancel
        // their alarm so a stale RUNNING entry can't fire after deletion.
        val removed = lastReacted.keys - seen
        for (id in removed) {
            lastReacted.remove(id)
            scheduler.cancel(id)
        }
    }

    private fun reactTo(msg: Message) {
        when (msg.timerState) {
            TimerState.RUNNING -> scheduleIfPending(msg)
            TimerState.CANCELLED, TimerState.COMPLETED -> scheduler.cancel(msg.id)
            null -> Unit
        }
    }

    private fun scheduleIfPending(msg: Message) {
        val startedAt = msg.timerStartedAtMs ?: return
        val duration = msg.timerDurationMs ?: return
        val fireAt = startedAt + duration
        // Past-fire-time messages are stale (e.g. process slept across the fire
        // moment). Skip — TimerAlarmReceiver / boot restore will reconcile, and
        // re-scheduling against a past instant fires immediately, which would
        // double-ring after the receiver also runs.
        if (fireAt <= nowMs()) return
        val result = scheduler.schedule(
            messageId = msg.id,
            fireAtMs = fireAt,
            caption = msg.content.takeIf { it.isNotBlank() },
            chatId = chatId,
            otherUserId = recipientId.takeIf { it.isNotEmpty() },
        )
        onScheduleResult(result)
    }

    private data class TimerSnapshot(val state: TimerState?, val fireAtMs: Long) {
        companion object {
            fun of(msg: Message): TimerSnapshot = TimerSnapshot(
                state = msg.timerState,
                fireAtMs = (msg.timerStartedAtMs ?: 0L) + (msg.timerDurationMs ?: 0L),
            )
        }
    }
}
