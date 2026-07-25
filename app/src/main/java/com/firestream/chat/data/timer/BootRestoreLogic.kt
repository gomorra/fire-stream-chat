package com.firestream.chat.data.timer

import com.firestream.chat.domain.model.TimerAlarmSound
import com.firestream.chat.domain.model.TimerAlarmStyle

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
    /**
     * Everything needed to re-arm the alarm *exactly* as it was first scheduled.
     *
     * [otherUserId], [style] and [sound] are carried explicitly because a reboot
     * used to drop them: the re-armed alarm passed `otherUserId = null` and no
     * silent flag, so after a restart a silent timer would ring and the
     * notification tap couldn't deep-link (`MainActivity.deepLinkFromIntent`
     * needs a sender id). Anything added to the alarm's parameters has to be
     * threaded through here too, or it survives only until the next reboot.
     */
    data class Schedule(
        val messageId: String,
        val chatId: String,
        val caption: String?,
        val fireAtMs: Long,
        val otherUserId: String?,
        val style: TimerAlarmStyle,
        val sound: TimerAlarmSound,
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
        otherUserId: String? = null,
        style: TimerAlarmStyle = TimerAlarmStyle.DEFAULT,
        sound: TimerAlarmSound = TimerAlarmSound.DEFAULT,
    ): TimerBootAction {
        val started = timerStartedAtMs ?: return TimerBootAction.Skip
        val duration = timerDurationMs ?: return TimerBootAction.Skip
        if (duration <= 0L) return TimerBootAction.Skip

        val fireAt = started + duration
        return if (fireAt > nowMs) {
            TimerBootAction.Schedule(
                messageId = messageId,
                chatId = chatId,
                caption = caption,
                fireAtMs = fireAt,
                otherUserId = otherUserId,
                style = style,
                sound = sound,
            )
        } else {
            TimerBootAction.MarkCompleted(messageId, chatId)
        }
    }

    /**
     * The chat partner to put on the re-armed alarm's deep link, or null when
     * there isn't exactly one — a group chat, a self-chat, or a participant list
     * that hasn't synced yet. Null matches what `ChatTimerReactor` passes for a
     * group, so boot restore and the live path agree.
     */
    fun resolveOtherUserId(participants: List<String>, currentUserId: String?): String? =
        participants.singleOrNull { it != currentUserId }
}
