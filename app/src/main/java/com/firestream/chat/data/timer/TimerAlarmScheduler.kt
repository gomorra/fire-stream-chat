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
    ): ScheduleResult {
        val pendingIntent = buildPendingIntent(
            messageId = messageId,
            caption = caption,
            chatId = chatId,
            otherUserId = otherUserId,
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
        val pendingIntent = buildPendingIntent(
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
        messageId: String,
        caption: String?,
        chatId: String?,
        otherUserId: String?,
        flags: Int,
    ): PendingIntent? {
        val intent = Intent(context, TimerAlarmReceiver::class.java).apply {
            // Action included so equality checks (which ignore extras but
            // consider action+component+data) line up across schedule/cancel.
            action = ACTION_TIMER_FIRED
            putExtra(EXTRA_MESSAGE_ID, messageId)
            if (caption != null) putExtra(EXTRA_CAPTION, caption)
            if (chatId != null) putExtra(EXTRA_CHAT_ID, chatId)
            if (otherUserId != null) putExtra(EXTRA_OTHER_USER_ID, otherUserId)
        }
        // messageId.hashCode() as request code: collisions in the 2^32 space are
        // negligible (~1% birthday at 9k concurrent timers); same-id collisions
        // are deliberate so re-scheduling replaces the prior alarm.
        return PendingIntent.getBroadcast(context, messageId.hashCode(), intent, flags)
    }

    companion object {
        const val ACTION_TIMER_FIRED: String = "com.firestream.chat.action.TIMER_FIRED"
        const val EXTRA_MESSAGE_ID: String = "message_id"
        const val EXTRA_CAPTION: String = "caption"
        const val EXTRA_CHAT_ID: String = "chat_id"
        const val EXTRA_OTHER_USER_ID: String = "other_user_id"
    }
}
