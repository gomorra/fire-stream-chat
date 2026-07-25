// region: AGENT-NOTE
// Responsibility: thin wrapper around AlarmManager for scheduling + cancelling
//   an exact wall-clock alarm per timer message. Owns nothing else; the broadcast
//   handler (TimerAlarmReceiver), notification channel (TimerNotificationChannel),
//   boot restore (BootCompletedReceiver + BootRestoreLogic), and the in-app
//   permission banner all live alongside in this package.
// Owns: AlarmManager registrations keyed by `messageId.hashCode()`. Each call to
//   schedule() with the same messageId replaces the previous PendingIntent
//   (FLAG_UPDATE_CURRENT) so re-scheduling on receipt is idempotent.
// Collaborators: AlarmManager (Hilt-provided in SystemModule), TimerAlarmReceiver
//   (the BroadcastReceiver woken by the PendingIntent), and ChatTimerReactor
//   which calls schedule()/cancel() in response to TIMER message-state changes.
// Don't put here: notification posting (that's TimerAlarmReceiver), Room writes
//   (the receiver invokes MessageRepository.markTimerCompleted), or banner UI
//   (the result enum is plumbed back to ChatCommandsManager.setExactAlarmBannerVisible).
// endregion

package com.firestream.chat.data.timer

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import com.firestream.chat.domain.model.TimerAlarmSound
import com.firestream.chat.domain.model.TimerAlarmStyle
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Outcome of [TimerAlarmScheduler.schedule]. The caller uses this to decide
 * whether to surface the in-app banner that asks the user to grant the
 * SCHEDULE_EXACT_ALARM special-app-access permission.
 *
 *  - [EXACT] — fire time is guaranteed to be honoured (up to OS scheduling).
 *  - [INEXACT_FALLBACK] — Android 12+ user denied SCHEDULE_EXACT_ALARM. Alarm
 *    will still fire eventually via setAndAllowWhileIdle but may be delayed up
 *    to ~15 min in Doze mode. UI should prompt the user to grant the permission.
 */
enum class ScheduleResult { EXACT, INEXACT_FALLBACK }

@Singleton
class TimerAlarmScheduler @Inject constructor(
    @ApplicationContext private val context: Context,
    private val alarmManager: AlarmManager,
) {

    /**
     * Schedule an exact alarm to fire at [fireAtMs] (wall-clock RTC, ms since
     * epoch). Re-scheduling with the same [messageId] replaces the prior alarm
     * via FLAG_UPDATE_CURRENT — safe to call repeatedly from the message
     * observer when state churns.
     */
    fun schedule(
        messageId: String,
        fireAtMs: Long,
        caption: String?,
        chatId: String,
        otherUserId: String?,
        style: TimerAlarmStyle = TimerAlarmStyle.DEFAULT,
        sound: TimerAlarmSound = TimerAlarmSound.DEFAULT,
    ): ScheduleResult {
        val pendingIntent = buildPendingIntent(
            action = ACTION_TIMER_FIRED,
            requestCode = messageId.hashCode(),
            messageId = messageId,
            caption = caption,
            chatId = chatId,
            otherUserId = otherUserId,
            style = style,
            sound = sound,
            flags = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        ) ?: return ScheduleResult.INEXACT_FALLBACK

        val canExact = Build.VERSION.SDK_INT < Build.VERSION_CODES.S || alarmManager.canScheduleExactAlarms()
        return if (canExact) {
            alarmManager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, fireAtMs, pendingIntent)
            ScheduleResult.EXACT
        } else {
            alarmManager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, fireAtMs, pendingIntent)
            ScheduleResult.INEXACT_FALLBACK
        }
    }

    /**
     * Cancel a previously scheduled alarm. No-op if nothing was scheduled —
     * we look up the existing PendingIntent with FLAG_NO_CREATE so cancel is
     * cheap and idempotent.
     */
    fun cancel(messageId: String) {
        cancelPending(ACTION_TIMER_FIRED, messageId.hashCode(), messageId)
        // A fired-but-unanswered timer may also have a nag queued; cancelling the
        // timer (or its message vanishing) must take that with it, or the re-alert
        // arrives for a timer that no longer exists.
        cancelRealert(messageId)
    }

    /**
     * Queue the follow-up nag for a [TimerAlarmStyle.NORMAL] timer that rang and
     * wasn't acknowledged. [attempt] counts from 1 and is echoed back so the
     * receiver knows when it has nagged enough.
     *
     * Inexact is fine here — unlike the timer itself, a nag being a few minutes
     * late costs nothing, and this avoids spending exact-alarm budget on it.
     */
    fun scheduleRealert(
        messageId: String,
        fireAtMs: Long,
        caption: String?,
        chatId: String,
        otherUserId: String?,
        style: TimerAlarmStyle,
        sound: TimerAlarmSound,
        attempt: Int,
    ) {
        val pendingIntent = buildPendingIntent(
            action = ACTION_TIMER_REALERT,
            requestCode = realertRequestCode(messageId),
            messageId = messageId,
            caption = caption,
            chatId = chatId,
            otherUserId = otherUserId,
            style = style,
            sound = sound,
            attempt = attempt,
            flags = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        ) ?: return
        alarmManager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, fireAtMs, pendingIntent)
    }

    fun cancelRealert(messageId: String) {
        cancelPending(ACTION_TIMER_REALERT, realertRequestCode(messageId), messageId)
    }

    private fun cancelPending(action: String, requestCode: Int, messageId: String) {
        val pendingIntent = buildPendingIntent(
            action = action,
            requestCode = requestCode,
            messageId = messageId,
            caption = null,
            chatId = null,
            otherUserId = null,
            flags = PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE,
        ) ?: return
        // Two-step cancel: AlarmManager.cancel() unschedules; PendingIntent.cancel()
        // invalidates the system token so a stale reference can't re-fire or be
        // matched by a future FLAG_NO_CREATE lookup.
        alarmManager.cancel(pendingIntent)
        pendingIntent.cancel()
    }

    private fun buildPendingIntent(
        action: String,
        requestCode: Int,
        messageId: String,
        caption: String?,
        chatId: String?,
        otherUserId: String?,
        style: TimerAlarmStyle? = null,
        sound: TimerAlarmSound? = null,
        attempt: Int? = null,
        flags: Int,
    ): PendingIntent? {
        val intent = Intent(context, TimerAlarmReceiver::class.java).apply {
            this.action = action
            putExtra(EXTRA_MESSAGE_ID, messageId)
            if (caption != null) putExtra(EXTRA_CAPTION, caption)
            if (chatId != null) putExtra(EXTRA_CHAT_ID, chatId)
            if (otherUserId != null) putExtra(EXTRA_OTHER_USER_ID, otherUserId)
            if (style != null) {
                putExtra(EXTRA_ALARM_STYLE, style.name)
                // Legacy boolean kept in step with the enum: an alarm scheduled by
                // this build can be delivered to a receiver from an older one after
                // a downgrade, and that receiver reads only EXTRA_SILENT.
                if (style.isSilent) putExtra(EXTRA_SILENT, true)
            }
            if (sound != null) putExtra(EXTRA_ALARM_SOUND, sound.name)
            if (attempt != null) putExtra(EXTRA_REALERT_ATTEMPT, attempt)
        }
        return PendingIntent.getBroadcast(context, requestCode, intent, flags)
    }

    private fun realertRequestCode(messageId: String): Int = messageId.hashCode() xor REQ_REALERT

    companion object {
        const val ACTION_TIMER_FIRED: String = "com.firestream.chat.action.TIMER_FIRED"
        const val ACTION_TIMER_REALERT: String = "com.firestream.chat.action.TIMER_REALERT"
        const val ACTION_TIMER_DISMISS: String = "com.firestream.chat.action.TIMER_DISMISS"
        const val EXTRA_MESSAGE_ID: String = "message_id"
        const val EXTRA_CAPTION: String = "caption"
        const val EXTRA_CHAT_ID: String = "chat_id"
        const val EXTRA_OTHER_USER_ID: String = "other_user_id"
        const val EXTRA_SILENT: String = "silent"
        const val EXTRA_ALARM_STYLE: String = "alarm_style"
        const val EXTRA_ALARM_SOUND: String = "alarm_sound"
        const val EXTRA_REALERT_ATTEMPT: String = "realert_attempt"

        /** Keeps the nag's PendingIntent from aliasing the timer's own. */
        private const val REQ_REALERT: Int = 0x4E46
    }
}
