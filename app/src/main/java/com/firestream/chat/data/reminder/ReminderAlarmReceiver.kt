package com.firestream.chat.data.reminder

import android.app.NotificationManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.firestream.chat.data.local.dao.ReminderDao
import com.firestream.chat.di.ApplicationScope
import com.firestream.chat.domain.repository.ReminderRepository
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import javax.inject.Inject

/**
 * Wakes when a [ReminderAlarmScheduler]-armed alarm fires, or when the user taps
 * one of the notification's action buttons. Handles three actions:
 *
 *  - [ReminderAlarmScheduler.ACTION_REMINDER_FIRED] — post the reminder
 *    notification (synchronously, so it's never lost), then delete the Room row:
 *    the reminder is consumed once fired (the bell disappears, the overview no
 *    longer lists it).
 *  - [ReminderAlarmScheduler.ACTION_REMINDER_SNOOZE_1H] — cancel the shown
 *    notification and re-arm a fresh reminder at now + 1h. The snapshot fields
 *    travel in the intent, so this works even though the row was already deleted
 *    on FIRED and even if the process had been killed in between.
 *  - [ReminderAlarmScheduler.ACTION_REMINDER_DONE] — cancel the shown
 *    notification and defensively delete any row (normally an idempotent no-op).
 *
 * `goAsync()` extends the receiver's lifetime so the suspend Room / repository
 * work has a chance to land before Android tears the process back down; the work
 * runs on [Dispatchers.IO] bounded well under goAsync()'s ~10s ceiling.
 */
@AndroidEntryPoint
class ReminderAlarmReceiver : BroadcastReceiver() {

    @Inject
    lateinit var reminderDao: ReminderDao

    @Inject
    lateinit var reminderRepository: ReminderRepository

    @Inject
    @ApplicationScope
    lateinit var appScope: CoroutineScope

    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            ReminderAlarmScheduler.ACTION_REMINDER_FIRED -> onFired(context, intent)
            ReminderAlarmScheduler.ACTION_REMINDER_SNOOZE_1H -> onSnooze(context, intent)
            ReminderAlarmScheduler.ACTION_REMINDER_DONE -> onDone(context, intent)
            else -> Unit
        }
    }

    private fun onFired(context: Context, intent: Intent) {
        val now = System.currentTimeMillis()
        val reminder = ReminderAlarmScheduler.reminderFromExtras(intent, fireAtMs = now, nowMs = now)
            ?: return

        // Post synchronously — the user-visible primary effect must not be lost
        // if the suspend delete below stalls.
        ReminderNotificationPoster.post(context, reminder)

        val pendingResult = goAsync()
        appScope.launch {
            try {
                withContext(Dispatchers.IO) {
                    withTimeoutOrNull(8_000L) {
                        reminderDao.deleteByMessageId(reminder.messageId)
                    }
                }
            } finally {
                pendingResult.finish()
            }
        }
    }

    private fun onSnooze(context: Context, intent: Intent) {
        val now = System.currentTimeMillis()
        val messageId = intent.getStringExtra(ReminderAlarmScheduler.EXTRA_MESSAGE_ID) ?: return
        cancelShownNotification(context, messageId)

        val newFireAt = ReminderActionLogic.plusOneHour(now)
        val reminder = ReminderAlarmScheduler.reminderFromExtras(intent, fireAtMs = newFireAt, nowMs = now)
            ?: return

        val pendingResult = goAsync()
        appScope.launch {
            try {
                withContext(Dispatchers.IO) {
                    withTimeoutOrNull(8_000L) {
                        // Writes the row and re-arms the alarm together.
                        reminderRepository.schedule(reminder)
                    }
                }
            } finally {
                pendingResult.finish()
            }
        }
    }

    private fun onDone(context: Context, intent: Intent) {
        val messageId = intent.getStringExtra(ReminderAlarmScheduler.EXTRA_MESSAGE_ID) ?: return
        cancelShownNotification(context, messageId)

        val pendingResult = goAsync()
        appScope.launch {
            try {
                withContext(Dispatchers.IO) {
                    withTimeoutOrNull(8_000L) {
                        // Idempotent: the row is usually already gone (deleted on FIRED).
                        reminderDao.deleteByMessageId(messageId)
                    }
                }
            } finally {
                pendingResult.finish()
            }
        }
    }

    private fun cancelShownNotification(context: Context, messageId: String) {
        val nm = context.getSystemService(NotificationManager::class.java) ?: return
        nm.cancel(ReminderNotificationPoster.NOTIFICATION_TAG, messageId.hashCode())
    }
}
