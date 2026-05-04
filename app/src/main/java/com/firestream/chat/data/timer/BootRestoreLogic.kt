package com.firestream.chat.data.timer

/**
 * Pure decision: given a single RUNNING timer's persisted fields and the
 * current wall-clock time, what should the boot path do with it?
 *
 *  - Future fire time → re-arm the [TimerAlarmScheduler] for it.
 *  - Past fire time   → flag [MarkCompleted] so the bubble flips to COMPLETED.
 *    No notification is posted; the moment was missed during the off-period
 *    and we don't want a stale ring to surprise the user at boot.
 *  - Corrupt / inconsistent rows → [Skip].
 *
 * Extracted from [BootCompletedReceiver] so we can unit-test the branching
 * without standing up a Robolectric context.
 */
internal sealed interface TimerBootAction {
    data class Schedule(
        val messageId: String,
        val chatId: String,
        val caption: String?,
        val fireAtMs: Long,
    ) : TimerBootAction
    data class MarkCompleted(val messageId: String, val chatId: String) : TimerBootAction
    data object Skip : TimerBootAction
}

internal object BootRestoreLogic {

    fun classify(
        messageId: String,
        chatId: String,
        caption: String?,
        timerStartedAtMs: Long?,
        timerDurationMs: Long?,
        nowMs: Long,
    ): TimerBootAction {
        val started = timerStartedAtMs ?: return TimerBootAction.Skip
        val duration = timerDurationMs ?: return TimerBootAction.Skip
        if (duration <= 0L) return TimerBootAction.Skip

        val fireAt = started + duration
        return if (fireAt > nowMs) {
            TimerBootAction.Schedule(messageId, chatId, caption, fireAt)
        } else {
            TimerBootAction.MarkCompleted(messageId, chatId)
        }
    }
}
