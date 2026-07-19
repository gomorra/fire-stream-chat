package com.firestream.chat.data.timer

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.firestream.chat.data.local.dao.MessageDao
import com.firestream.chat.data.local.dao.ReminderDao
import com.firestream.chat.data.reminder.ReminderAlarmScheduler
import com.firestream.chat.data.reminder.ReminderBootAction
import com.firestream.chat.data.reminder.ReminderBootRestoreLogic
import com.firestream.chat.data.reminder.ReminderNotificationPoster
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
 * Re-arms alarms after a device reboot for **both** the timer and the
 * message-reminder features — a single boot receiver avoids a second exported
 * BOOT_COMPLETED entry point.
 *
 *  - Timers: every running timer's `setExactAndAllowWhileIdle` registration is
 *    dropped by the OS at shutdown — the message stays RUNNING in Room and
 *    Firestore but never rings unless re-armed here.
 *  - Reminders: same alarm-drop applies. Future reminders are re-scheduled; ones
 *    whose fire time was missed during the off-period post an "(overdue)"
 *    notification immediately and delete their row.
 *
 * Both branches keep their decision in a pure logic class ([BootRestoreLogic],
 * [ReminderBootRestoreLogic]) so the future-vs-past choice is unit-testable
 * without a Robolectric context.
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
    lateinit var reminderDao: ReminderDao

    @Inject
    lateinit var reminderScheduler: ReminderAlarmScheduler

    @Inject
    @ApplicationScope
    lateinit var appScope: CoroutineScope

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return

        val pendingResult = goAsync()
        appScope.launch {
            try {
                val now = System.currentTimeMillis()
                // Run on IO and bound the whole batch by 8s — under goAsync()'s
                // ~10s ceiling. Local AlarmManager re-arms run first per dispatch,
                // so future-fire survivors get scheduled even if the batch is cut.
                withContext(Dispatchers.IO) {
                    withTimeoutOrNull(8_000L) {
                        coroutineScope {
                            val timers = messageDao.getRunningTimers().map { entity ->
                                async { dispatchTimer(entity, now) }
                            }
                            val reminders = reminderDao.getAll().map { entity ->
                                async { dispatchReminder(context, entity, now) }
                            }
                            (timers + reminders).awaitAll()
                        }
                    }
                }
            } finally {
                pendingResult.finish()
            }
        }
    }

    private suspend fun dispatchTimer(entity: com.firestream.chat.data.local.entity.MessageEntity, now: Long) {
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

    private suspend fun dispatchReminder(
        context: Context,
        entity: com.firestream.chat.data.local.entity.ReminderEntity,
        now: Long,
    ) {
        when (ReminderBootRestoreLogic.classify(fireAtMs = entity.fireAtMs, nowMs = now)) {
            ReminderBootAction.Schedule -> reminderScheduler.schedule(entity.toDomain())
            ReminderBootAction.PostOverdue -> {
                ReminderNotificationPoster.post(context, entity.toDomain(), overdue = true)
                reminderDao.deleteByMessageId(entity.messageId)
            }
        }
    }
}
