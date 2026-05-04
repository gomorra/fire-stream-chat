package com.firestream.chat.data.timer

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.firestream.chat.data.local.dao.MessageDao
import com.firestream.chat.di.ApplicationScope
import com.firestream.chat.domain.repository.MessageRepository
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
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
                // Parallelise: each MarkCompleted is a Firestore RPC and the
                // receiver only has ~10s before Android tears the process back
                // down. Sequential iteration of N stale timers easily blows that
                // budget on a slow network.
                coroutineScope {
                    running.map { entity ->
                        async { dispatch(entity, now) }
                    }.awaitAll()
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
