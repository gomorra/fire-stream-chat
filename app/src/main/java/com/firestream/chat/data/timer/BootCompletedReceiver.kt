package com.firestream.chat.data.timer

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.firestream.chat.data.local.dao.MessageDao
import com.firestream.chat.di.ApplicationScope
import com.firestream.chat.domain.repository.MessageRepository
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import javax.inject.Inject

/**
 * Re-arms timer alarms after a device reboot. Without this, every running
 * timer's `setExactAndAllowWhileIdle` registration is dropped by the OS at
 * shutdown — the message stays RUNNING in Room and Firestore but never rings.
 *
 * Branching delegated to [BootRestoreLogic] so the future-vs-past decision is
 * unit-testable without Robolectric.
 */
@AndroidEntryPoint
class BootCompletedReceiver : BroadcastReceiver() {

    @Inject
    lateinit var messageDao: MessageDao

    @Inject
    lateinit var messageRepository: MessageRepository

    @Inject
    lateinit var scheduler: TimerAlarmScheduler

    @Inject
    @ApplicationScope
    lateinit var appScope: CoroutineScope

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return

        val pendingResult = goAsync()
        appScope.launch {
            try {
                val now = System.currentTimeMillis()
                val running = messageDao.getRunningTimers()
                // Run on IO and bound the whole batch by 8s — under goAsync()'s
                // ~10s ceiling. A slow network with N stale timers would otherwise
                // blow past the budget; survivors at least get re-armed for the
                // future-fire case before forced finish, since scheduler.schedule
                // is a local AlarmManager call and runs first per dispatch().
                withContext(Dispatchers.IO) {
                    withTimeoutOrNull(8_000L) {
                        coroutineScope {
                            running.map { entity ->
                                async { dispatch(entity, now) }
                            }.awaitAll()
                        }
                    }
                }
            } finally {
                pendingResult.finish()
            }
        }
    }

    private suspend fun dispatch(entity: com.firestream.chat.data.local.entity.MessageEntity, now: Long) {
        val action = BootRestoreLogic.classify(
            messageId = entity.id,
            chatId = entity.chatId,
            caption = entity.content.takeIf { it.isNotBlank() },
            timerStartedAtMs = entity.timerStartedAtMs,
            timerDurationMs = entity.timerDurationMs,
            nowMs = now,
        )
        when (action) {
            is TimerBootAction.Schedule -> scheduler.schedule(
                messageId = action.messageId,
                fireAtMs = action.fireAtMs,
                caption = action.caption,
                chatId = action.chatId,
                otherUserId = null,
            )
            is TimerBootAction.MarkCompleted ->
                messageRepository.markTimerCompleted(action.chatId, action.messageId)
            TimerBootAction.Skip -> Unit
        }
    }
}
