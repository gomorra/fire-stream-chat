package com.firestream.chat.data.timer

import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import com.firestream.chat.MainActivity
import com.firestream.chat.R
import com.firestream.chat.di.ApplicationScope
import com.firestream.chat.domain.model.TimerAlarmSound
import com.firestream.chat.domain.model.TimerAlarmStyle
import com.firestream.chat.domain.repository.MessageRepository
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Handles every stage of a timer's alarm, keyed by intent action:
 *
 *  - [TimerAlarmScheduler.ACTION_TIMER_FIRED] — the timer reached its fire time.
 *    Posts the alarm notification and flips the message to `COMPLETED` so both
 *    devices' bubbles update. `goAsync()` extends the receiver's life to ~10s so
 *    the suspend call can land before the process is torn down.
 *  - [TimerAlarmScheduler.ACTION_TIMER_REALERT] — the nag. A `NORMAL` timer that
 *    rang once and was never acknowledged rings again, up to [MAX_REALERTS] times.
 *  - [TimerAlarmScheduler.ACTION_TIMER_DISMISS] — the notification's Dismiss
 *    button. Clears the notification (which is also what silences an insistent
 *    ring) and drops any queued nag.
 *
 * ### Why an insistent alarm needs a timeout
 * `FLAG_INSISTENT` loops the sound until the notification is cancelled, and the
 * platform imposes no maximum of its own. Unattended — phone in a bag, timer
 * fired in another room — that means ringing until the battery dies, so every
 * insistent notification carries [setTimeoutAfter] as a backstop. Dismiss handles
 * the case where someone is present; the timeout handles the case where nobody is.
 */
@AndroidEntryPoint
class TimerAlarmReceiver : BroadcastReceiver() {

    @Inject
    lateinit var messageRepository: MessageRepository

    @Inject
    lateinit var scheduler: TimerAlarmScheduler

    @Inject
    @ApplicationScope
    lateinit var appScope: CoroutineScope

    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            TimerAlarmScheduler.ACTION_TIMER_FIRED -> handleFired(context, intent)
            TimerAlarmScheduler.ACTION_TIMER_REALERT -> handleRealert(context, intent)
            TimerAlarmScheduler.ACTION_TIMER_DISMISS -> handleDismiss(context, intent)
        }
    }

    private fun handleFired(context: Context, intent: Intent) {
        val alarm = TimerAlarmRequest.from(intent) ?: return

        // Post synchronously — it's the user-visible primary effect and we don't
        // want to lose it if the suspend call below stalls. Silent timers skip the
        // notification entirely but still flip to COMPLETED.
        if (!alarm.style.isSilent) {
            postAlarmNotification(context, alarm)
            queueRealert(alarm, attempt = 1)
        }

        // The state flip is best-effort: if the network is down, the next observer
        // reconciliation on either device will catch up because the local Room
        // entry is also marked completed by markTimerCompleted.
        val pendingResult = goAsync()
        appScope.launch {
            try {
                messageRepository.markTimerCompleted(alarm.chatId, alarm.messageId)
            } finally {
                pendingResult.finish()
            }
        }
    }

    /**
     * Ring again if — and only if — the notification is still sitting there
     * unacknowledged. Tapping it, swiping it away, or pressing Dismiss all remove
     * it, and any of those means the user knows, so the nag stops on its own
     * without needing a separate "acknowledged" flag anywhere.
     */
    private fun handleRealert(context: Context, intent: Intent) {
        val alarm = TimerAlarmRequest.from(intent) ?: return
        val nm = context.getSystemService(NotificationManager::class.java) ?: return
        if (!isNotificationActive(nm, alarm.messageId)) return

        postAlarmNotification(context, alarm)
        queueRealert(alarm, attempt = alarm.realertAttempt + 1)
    }

    private fun handleDismiss(context: Context, intent: Intent) {
        val messageId = intent.getStringExtra(TimerAlarmScheduler.EXTRA_MESSAGE_ID) ?: return
        context.getSystemService(NotificationManager::class.java)
            ?.cancel(NOTIFICATION_TAG, messageId.hashCode())
        scheduler.cancelRealert(messageId)
    }

    /**
     * Nagging applies to [TimerAlarmStyle.NORMAL] only. An insistent timer is
     * already making continuous noise, so a nag on top of it would be meaningless;
     * a silent one never posts a notification to nag about.
     */
    private fun queueRealert(alarm: TimerAlarmRequest, attempt: Int) {
        if (alarm.style != TimerAlarmStyle.NORMAL) return
        if (attempt > MAX_REALERTS) return
        scheduler.scheduleRealert(
            messageId = alarm.messageId,
            fireAtMs = System.currentTimeMillis() + REALERT_INTERVAL_MS,
            caption = alarm.caption,
            chatId = alarm.chatId,
            otherUserId = alarm.otherUserId,
            style = alarm.style,
            sound = alarm.sound,
            attempt = attempt,
        )
    }

    private fun isNotificationActive(nm: NotificationManager, messageId: String): Boolean =
        nm.activeNotifications.any { it.tag == NOTIFICATION_TAG && it.id == messageId.hashCode() }

    private fun postAlarmNotification(context: Context, alarm: TimerAlarmRequest) {
        val messageId = alarm.messageId
        val openChatPending = PendingIntent.getActivity(
            context,
            messageId.hashCode(),
            buildOpenChatIntent(context, messageId, alarm.chatId, alarm.otherUserId),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        val title = if (alarm.caption.isNullOrBlank()) "Timer ended" else alarm.caption
        val insistent = alarm.style == TimerAlarmStyle.INSISTENT

        val builder = NotificationCompat.Builder(
            context,
            TimerNotificationChannel.channelIdFor(alarm.sound),
        )
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle(title)
            .setContentText(if (insistent) "Tap to open the chat · ringing" else "Tap to open the chat")
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setAutoCancel(true)
            .setContentIntent(openChatPending)
            .setFullScreenIntent(openChatPending, true)
            .addAction(
                NotificationCompat.Action.Builder(
                    0,
                    "Dismiss",
                    dismissIntent(context, messageId),
                ).build(),
            )

        // See the class KDoc: an insistent ring has no natural end, so it gets one.
        if (insistent) builder.setTimeoutAfter(AUTO_SILENCE_MS)

        val notification = builder.build().apply {
            if (insistent) flags = flags or Notification.FLAG_INSISTENT
        }

        val nm = context.getSystemService(NotificationManager::class.java) ?: return
        nm.notify(NOTIFICATION_TAG, messageId.hashCode(), notification)
    }

    private fun dismissIntent(context: Context, messageId: String): PendingIntent {
        val intent = Intent(context, TimerAlarmReceiver::class.java).apply {
            action = TimerAlarmScheduler.ACTION_TIMER_DISMISS
            putExtra(TimerAlarmScheduler.EXTRA_MESSAGE_ID, messageId)
        }
        return PendingIntent.getBroadcast(
            context,
            messageId.hashCode() xor REQ_DISMISS,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    companion object {
        private const val NOTIFICATION_TAG: String = "timer_alarm"

        /** Distinguishes the Dismiss PendingIntent from the content-tap one. */
        private const val REQ_DISMISS: Int = 0xD152

        /**
         * How long an insistent alarm may ring unattended. AOSP's clock app uses
         * 10 minutes, which is a long time for a chat timer — 2 minutes is enough
         * to cross a room without being punitive if nobody is home.
         */
        internal const val AUTO_SILENCE_MS: Long = 2 * 60 * 1000L

        /** Gap between a NORMAL timer's nags. */
        internal const val REALERT_INTERVAL_MS: Long = 60 * 1000L

        /** Nags after the first ring, so a timer rings at most 1 + this many times. */
        internal const val MAX_REALERTS: Int = 2

        /**
         * Intent behind the notification tap: open [chatId] **and** hand the chat
         * screen a jump target so it scrolls to the timer bubble and flashes it
         * (`ChatScreen`'s deep-link effect, same 1.5s highlight the reply-preview
         * and reminder jumps use).
         *
         * [MainActivity.EXTRA_MESSAGE_ID] is what makes it a *targeted* open —
         * without it the deep link only carried chat + sender, so a tap landed on
         * the newest message and the timer that just rang was left unmarked.
         *
         * `otherUserId` is still conditional: `MainActivity.deepLinkFromIntent`
         * requires a sender id, so a timer scheduled with none (empty recipient)
         * opens the app without navigating — pre-existing, shared with the FCM path.
         */
        internal fun buildOpenChatIntent(
            context: Context,
            messageId: String,
            chatId: String,
            otherUserId: String?,
        ): Intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
            putExtra(MainActivity.EXTRA_CHAT_ID, chatId)
            putExtra(MainActivity.EXTRA_MESSAGE_ID, messageId)
            if (otherUserId != null) putExtra(MainActivity.EXTRA_SENDER_ID, otherUserId)
        }
    }
}

/**
 * The alarm parameters carried on a scheduler intent, parsed once.
 *
 * Extracted from the receiver so the extras→values decoding — in particular the
 * legacy fallback — is unit-testable without a Robolectric receiver harness.
 */
internal data class TimerAlarmRequest(
    val messageId: String,
    val chatId: String,
    val caption: String?,
    val otherUserId: String?,
    val style: TimerAlarmStyle,
    val sound: TimerAlarmSound,
    val realertAttempt: Int,
) {
    companion object {
        /**
         * Returns null when the intent lacks the ids the alarm can't work without.
         *
         * **Legacy path that must not regress:** a `PendingIntent` scheduled by a
         * build predating the style/sound extras survives an app update and will be
         * delivered to *this* receiver. Those intents carry only `EXTRA_SILENT`, so
         * an absent style falls back to it — otherwise updating the app would make
         * every already-running silent timer ring.
         */
        fun from(intent: Intent): TimerAlarmRequest? {
            val messageId = intent.getStringExtra(TimerAlarmScheduler.EXTRA_MESSAGE_ID) ?: return null
            val chatId = intent.getStringExtra(TimerAlarmScheduler.EXTRA_CHAT_ID) ?: return null

            val legacySilent = intent.getBooleanExtra(TimerAlarmScheduler.EXTRA_SILENT, false)
            val style = intent.getStringExtra(TimerAlarmScheduler.EXTRA_ALARM_STYLE)
                ?.let { name -> runCatching { TimerAlarmStyle.valueOf(name) }.getOrNull() }
                ?: TimerAlarmStyle.fromLegacySilent(legacySilent)
            val sound = intent.getStringExtra(TimerAlarmScheduler.EXTRA_ALARM_SOUND)
                ?.let { name -> runCatching { TimerAlarmSound.valueOf(name) }.getOrNull() }
                ?: TimerAlarmSound.DEFAULT

            return TimerAlarmRequest(
                messageId = messageId,
                chatId = chatId,
                caption = intent.getStringExtra(TimerAlarmScheduler.EXTRA_CAPTION),
                otherUserId = intent.getStringExtra(TimerAlarmScheduler.EXTRA_OTHER_USER_ID),
                style = style,
                sound = sound,
                realertAttempt = intent.getIntExtra(TimerAlarmScheduler.EXTRA_REALERT_ATTEMPT, 0),
            )
        }
    }
}
