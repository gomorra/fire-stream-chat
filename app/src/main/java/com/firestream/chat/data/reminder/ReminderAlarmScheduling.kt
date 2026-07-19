package com.firestream.chat.data.reminder

import com.firestream.chat.domain.model.Reminder
import com.firestream.chat.domain.model.ReminderScheduleOutcome

/**
 * Arms/disarms the OS-level alarm for a reminder. The real implementation is
 * [ReminderAlarmScheduler]; kept as an interface so [ReminderRepositoryImpl] can
 * be unit-tested against a fake without an AlarmManager.
 *
 * [schedule] returns a [ReminderScheduleOutcome] so the caller can plumb the
 * `INEXACT_FALLBACK` case back to the shared `ExactAlarmBanner` flow (Android 12+
 * exact-alarm permission denied).
 */
interface ReminderAlarmScheduling {
    fun schedule(reminder: Reminder): ReminderScheduleOutcome
    fun cancel(messageId: String)
}
