package com.firestream.chat.data.reminder

import com.firestream.chat.domain.model.Reminder
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Arms/disarms the OS-level alarm for a reminder. Step 2 of the
 * message-snooze-reminders plan (`.claude/plans/message-snooze-reminders.md`)
 * replaces the [NoOpReminderAlarmScheduling] binding with a real
 * `ReminderAlarmScheduler` (clone of `data/timer/TimerAlarmScheduler`) that
 * sets an exact alarm and posts the fired notification.
 */
interface ReminderAlarmScheduling {
    fun schedule(reminder: Reminder)
    fun cancel(messageId: String)
}

/**
 * Temporary no-op implementation so Step 1 can wire the DI graph and Room
 * persistence end-to-end before the real alarm pipeline exists. Reminders are
 * durably written to Room but nothing actually fires until Step 2 lands.
 */
@Singleton
class NoOpReminderAlarmScheduling @Inject constructor() : ReminderAlarmScheduling {
    override fun schedule(reminder: Reminder) {
        // Intentionally no-op — see Step 2 of the plan.
    }

    override fun cancel(messageId: String) {
        // Intentionally no-op — see Step 2 of the plan.
    }
}
