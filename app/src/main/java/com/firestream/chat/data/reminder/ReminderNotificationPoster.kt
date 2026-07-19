package com.firestream.chat.data.reminder

import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import com.firestream.chat.MainActivity
import com.firestream.chat.R
import com.firestream.chat.data.reminder.ReminderAlarmScheduler.Companion.putReminderExtras
import com.firestream.chat.domain.model.Reminder

/**
 * Builds and posts the message-reminder notification. Extracted from
 * [ReminderAlarmReceiver] so the boot-restore path
 * ([BootCompletedReceiver][com.firestream.chat.data.timer.BootCompletedReceiver])
 * can post an "(overdue)" reminder with the same layout.
 *
 * Notification identity: tag [NOTIFICATION_TAG] + id `messageId.hashCode()`. The
 * dedicated tag keeps reminder notifications in a namespace disjoint from the
 * timer feature's `"timer_alarm"` tag, so a reminder and a timer that happen to
 * hash to the same id can coexist without clobbering each other (risk R3).
 */
internal object ReminderNotificationPoster {

    const val NOTIFICATION_TAG: String = "message_reminder"

    // messageId.hashCode() is xor-ed with these so the content-tap intent and the
    // two action-button PendingIntents get distinct request codes and don't alias.
    private const val REQ_CONTENT: Int = 0
    private const val REQ_SNOOZE: Int = 0x5A00
    private const val REQ_DONE: Int = 0xD0E0

    fun post(context: Context, reminder: Reminder, overdue: Boolean = false) {
        val nm = context.getSystemService(NotificationManager::class.java) ?: return
        val messageId = reminder.messageId

        val title = if (overdue) "(overdue) ${reminder.senderNameSnapshot}" else reminder.senderNameSnapshot

        val notification = NotificationCompat.Builder(context, ReminderNotificationChannel.CHANNEL_ID)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle(title)
            .setContentText(reminder.messageSnapshot)
            .setStyle(NotificationCompat.BigTextStyle().bigText(reminder.messageSnapshot))
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .setAutoCancel(true)
            .setContentIntent(contentIntent(context, reminder))
            .addAction(
                NotificationCompat.Action.Builder(0, "+1 hour", snoozeIntent(context, reminder)).build(),
            )
            .addAction(
                NotificationCompat.Action.Builder(0, "Done", doneIntent(context, reminder)).build(),
            )
            .build()

        nm.notify(NOTIFICATION_TAG, messageId.hashCode(), notification)
    }

    /** Content tap → open the chat and scroll to the source message. */
    private fun contentIntent(context: Context, reminder: Reminder): PendingIntent {
        val openChat = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
            putExtra(MainActivity.EXTRA_CHAT_ID, reminder.chatId)
            putExtra(MainActivity.EXTRA_SENDER_ID, reminder.recipientId)
            // Step 4 replaces "messageId" with MainActivity.EXTRA_MESSAGE_ID.
            putExtra("messageId", reminder.messageId)
        }
        return PendingIntent.getActivity(
            context,
            reminder.messageId.hashCode() xor REQ_CONTENT,
            openChat,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    /** "+1 hour" → broadcast back to [ReminderAlarmReceiver] carrying the snapshots. */
    private fun snoozeIntent(context: Context, reminder: Reminder): PendingIntent {
        val intent = Intent(context, ReminderAlarmReceiver::class.java).apply {
            action = ReminderAlarmScheduler.ACTION_REMINDER_SNOOZE_1H
            putReminderExtras(reminder)
        }
        return PendingIntent.getBroadcast(
            context,
            reminder.messageId.hashCode() xor REQ_SNOOZE,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    /** "Done" → broadcast back to [ReminderAlarmReceiver]; only needs the id. */
    private fun doneIntent(context: Context, reminder: Reminder): PendingIntent {
        val intent = Intent(context, ReminderAlarmReceiver::class.java).apply {
            action = ReminderAlarmScheduler.ACTION_REMINDER_DONE
            putExtra(ReminderAlarmScheduler.EXTRA_MESSAGE_ID, reminder.messageId)
        }
        return PendingIntent.getBroadcast(
            context,
            reminder.messageId.hashCode() xor REQ_DONE,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }
}
