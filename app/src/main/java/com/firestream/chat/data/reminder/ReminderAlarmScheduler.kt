// region: AGENT-NOTE
// Responsibility: thin wrapper around AlarmManager for arming + cancelling an
//   exact wall-clock alarm per snoozed message. Cloned from the timer feature's
//   TimerAlarmScheduler; the broadcast handler (ReminderAlarmReceiver),
//   notification channel (ReminderNotificationChannel), notification builder
//   (ReminderNotificationPoster), and pure boot/action logic all live alongside
//   in this package.
// Owns: AlarmManager registrations keyed by `messageId.hashCode()`. Each
//   schedule() with the same messageId replaces the previous PendingIntent
//   (FLAG_UPDATE_CURRENT) so re-scheduling on re-snooze is idempotent. Also owns
//   the intent action + extra keys reused by ReminderNotificationPoster (fired
//   notification content + action buttons) and ReminderAlarmReceiver.
// Collaborators: AlarmManager (Hilt-provided in SystemModule), ReminderAlarmReceiver
//   (woken by the PendingIntent), ReminderRepositoryImpl (calls schedule/cancel
//   via the ReminderAlarmScheduling interface it implements).
// Don't put here: notification posting (ReminderNotificationPoster), Room writes
//   (ReminderRepositoryImpl / the receiver), or banner UI (the outcome enum is
//   plumbed back through ReminderRepository to the shared ExactAlarmBanner flow).
// endregion

package com.firestream.chat.data.reminder

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import com.firestream.chat.domain.model.Reminder
import com.firestream.chat.domain.model.ReminderScheduleOutcome
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Real [ReminderAlarmScheduling] — arms an exact wall-clock alarm that wakes
 * [ReminderAlarmReceiver] at the reminder's [Reminder.fireAtMs]. Mirrors
 * `TimerAlarmScheduler`: exact alarm when permitted, `setAndAllowWhileIdle`
 * fallback (surfaced as [ReminderScheduleOutcome.INEXACT_FALLBACK]) otherwise.
 */
@Singleton
class ReminderAlarmScheduler @Inject constructor(
    @ApplicationContext private val context: Context,
    private val alarmManager: AlarmManager,
) : ReminderAlarmScheduling {

    /**
     * Arm an exact alarm to fire at [Reminder.fireAtMs] (wall-clock RTC, ms
     * since epoch). Re-scheduling with the same messageId replaces the prior
     * alarm via FLAG_UPDATE_CURRENT — safe to call repeatedly on re-snooze.
     */
    override fun schedule(reminder: Reminder): ReminderScheduleOutcome {
        val pendingIntent = buildFiredPendingIntent(
            reminder = reminder,
            flags = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        ) ?: return ReminderScheduleOutcome.INEXACT_FALLBACK

        val canExact =
            Build.VERSION.SDK_INT < Build.VERSION_CODES.S || alarmManager.canScheduleExactAlarms()
        return if (canExact) {
            alarmManager.setExactAndAllowWhileIdle(
                AlarmManager.RTC_WAKEUP,
                reminder.fireAtMs,
                pendingIntent,
            )
            ReminderScheduleOutcome.EXACT
        } else {
            alarmManager.setAndAllowWhileIdle(
                AlarmManager.RTC_WAKEUP,
                reminder.fireAtMs,
                pendingIntent,
            )
            ReminderScheduleOutcome.INEXACT_FALLBACK
        }
    }

    /**
     * Cancel a previously scheduled alarm. No-op if nothing was scheduled — we
     * look up the existing PendingIntent with FLAG_NO_CREATE so cancel is cheap
     * and idempotent.
     */
    override fun cancel(messageId: String) {
        val intent = Intent(context, ReminderAlarmReceiver::class.java).apply {
            action = ACTION_REMINDER_FIRED
            putExtra(EXTRA_MESSAGE_ID, messageId)
        }
        val pendingIntent = PendingIntent.getBroadcast(
            context,
            messageId.hashCode(),
            intent,
            PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE,
        ) ?: return
        // Two-step cancel: AlarmManager.cancel() unschedules; PendingIntent.cancel()
        // invalidates the system token so a stale reference can't re-fire or be
        // matched by a future FLAG_NO_CREATE lookup.
        alarmManager.cancel(pendingIntent)
        pendingIntent.cancel()
    }

    private fun buildFiredPendingIntent(reminder: Reminder, flags: Int): PendingIntent? {
        val intent = Intent(context, ReminderAlarmReceiver::class.java).apply {
            action = ACTION_REMINDER_FIRED
            putReminderExtras(reminder)
        }
        return PendingIntent.getBroadcast(context, reminder.messageId.hashCode(), intent, flags)
    }

    companion object {
        const val ACTION_REMINDER_FIRED: String = "com.firestream.chat.action.REMINDER_FIRED"
        const val ACTION_REMINDER_SNOOZE_1H: String = "com.firestream.chat.action.REMINDER_SNOOZE_1H"
        const val ACTION_REMINDER_DONE: String = "com.firestream.chat.action.REMINDER_DONE"

        const val EXTRA_MESSAGE_ID: String = "reminder_message_id"
        const val EXTRA_CHAT_ID: String = "reminder_chat_id"
        const val EXTRA_RECIPIENT_ID: String = "reminder_recipient_id"
        const val EXTRA_SNAPSHOT: String = "reminder_snapshot"
        const val EXTRA_SENDER_NAME: String = "reminder_sender_name"
        const val EXTRA_FIRE_AT: String = "reminder_fire_at"

        /**
         * Stamp a [Reminder]'s snapshot fields onto [intent]. The snapshots travel
         * inside every reminder intent (fired alarm + action buttons) precisely so
         * the SNOOZE_1H handler can rebuild a [Reminder] even after the Room row was
         * deleted on FIRED — or after the whole process was killed.
         */
        fun Intent.putReminderExtras(reminder: Reminder): Intent = apply {
            putExtra(EXTRA_MESSAGE_ID, reminder.messageId)
            putExtra(EXTRA_CHAT_ID, reminder.chatId)
            putExtra(EXTRA_RECIPIENT_ID, reminder.recipientId)
            putExtra(EXTRA_SNAPSHOT, reminder.messageSnapshot)
            putExtra(EXTRA_SENDER_NAME, reminder.senderNameSnapshot)
            putExtra(EXTRA_FIRE_AT, reminder.fireAtMs)
        }

        /**
         * Rebuild a [Reminder] from intent extras. `id` collapses to [messageId]
         * (one reminder per message) and `createdAtMs` to [nowMs] — neither the
         * fired notification nor a re-snooze depends on the original values.
         * Returns null if the required identity extras are missing.
         */
        fun reminderFromExtras(intent: Intent, fireAtMs: Long, nowMs: Long): Reminder? {
            val messageId = intent.getStringExtra(EXTRA_MESSAGE_ID) ?: return null
            val chatId = intent.getStringExtra(EXTRA_CHAT_ID) ?: return null
            val recipientId = intent.getStringExtra(EXTRA_RECIPIENT_ID) ?: return null
            return Reminder(
                id = messageId,
                messageId = messageId,
                chatId = chatId,
                recipientId = recipientId,
                fireAtMs = fireAtMs,
                messageSnapshot = intent.getStringExtra(EXTRA_SNAPSHOT).orEmpty(),
                senderNameSnapshot = intent.getStringExtra(EXTRA_SENDER_NAME).orEmpty(),
                createdAtMs = nowMs,
            )
        }
    }
}
