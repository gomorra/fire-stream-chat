package com.firestream.chat.data.reminder

/**
 * Pure decision: given a single pending reminder's fire time and the current
 * wall-clock time, what should the boot path do with it?
 *
 *  - Future fire time → [Schedule] it — re-arm the [ReminderAlarmScheduler],
 *    since every AlarmManager registration is dropped by the OS at shutdown.
 *  - Past fire time → [PostOverdue]: the moment was missed during the off-period,
 *    so post the notification immediately (prefixed "(overdue) ") and delete the
 *    row. Unlike the timer feature — which silently marks past timers completed —
 *    a reminder the user explicitly set should never be silently dropped.
 *
 * Extracted from [BootCompletedReceiver][com.firestream.chat.data.timer.BootCompletedReceiver]
 * so the future-vs-past branching is unit-testable without a Robolectric context.
 */
internal sealed interface ReminderBootAction {
    /** Future fire time — re-arm the alarm for [fireAtMs]. */
    data object Schedule : ReminderBootAction

    /** Past fire time — post the "(overdue)" notification now and delete the row. */
    data object PostOverdue : ReminderBootAction
}

internal object ReminderBootRestoreLogic {

    /**
     * A fire time exactly equal to [nowMs] is treated as overdue: re-arming an
     * alarm for the current instant would fire immediately anyway, so posting
     * the overdue notification directly is the equivalent, simpler branch.
     */
    fun classify(fireAtMs: Long, nowMs: Long): ReminderBootAction =
        if (fireAtMs > nowMs) ReminderBootAction.Schedule else ReminderBootAction.PostOverdue
}
