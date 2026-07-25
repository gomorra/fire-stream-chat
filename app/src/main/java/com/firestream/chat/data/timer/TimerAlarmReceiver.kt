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
import com.firestream.chat.data.util.resolveTimerAlarmSound
import com.firestream.chat.data.util.resolveTimerAlarmStyle
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
     * Queue the next ring of [alarm]'s escalation, if it has one left.
     *
     * Both audible styles escalate through this same re-post chain. It would be
     * tempting to skip it for [TimerAlarmStyle.INSISTENT] on the grounds that
     * `FLAG_INSISTENT` already loops the sound — but that flag is the one part of
     * this feature whose behaviour is not verified on real hardware, and OEMs vary.
     * If it turned out not to loop, an insistent timer without this chain would
     * ring exactly once with no follow-up: *quieter than NORMAL*, while the picker
     * promises it keeps going. Driving both from the chain makes the flag purely
     * additive — it makes an insistent alarm continuous rather than repeating, and
     * nothing depends on it working.
     */
    private fun queueRealert(alarm: TimerAlarmRequest, attempt: Int) {
        val escalation = escalationFor(alarm.style) ?: return
        if (attempt > escalation.maxRepeats) return
        scheduler.scheduleRealert(
            messageId = alarm.messageId,
            fireAtMs = System.currentTimeMillis() + escalation.intervalMs,
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
        // The window shrinks with each re-post so the *total* stays AUTO_SILENCE_MS
        // rather than restarting the clock on every ring of the chain.
        if (insistent) builder.setTimeoutAfter(alarm.remainingRingMs())

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
         *
         * Held to exactly `INSISTENT` interval × (repeats + 1) so the escalation
         * chain and the auto-silence deadline end together instead of one cutting
         * the other short. `TimerEscalationTest` pins that.
         */
        internal const val AUTO_SILENCE_MS: Long = 2 * 60 * 1000L

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
 * How often a fired-but-unacknowledged alarm rings again, and how many times.
 *
 * One policy per style, driving one mechanism — see `queueRealert` for why
 * INSISTENT escalates through the chain rather than relying on `FLAG_INSISTENT`.
 */
internal data class AlarmEscalation(val intervalMs: Long, val maxRepeats: Int)

/** Null for [TimerAlarmStyle.SILENT], which posts nothing to escalate. */
internal fun escalationFor(style: TimerAlarmStyle): AlarmEscalation? = when (style) {
    TimerAlarmStyle.SILENT -> null
    // Sparse and finite: a nudge for someone who's simply away from the phone.
    TimerAlarmStyle.NORMAL -> AlarmEscalation(intervalMs = 60_000L, maxRepeats = 2)
    // Dense and bounded by the auto-silence window: 30s × (3 + the initial ring)
    // is exactly TimerAlarmReceiver.AUTO_SILENCE_MS.
    TimerAlarmStyle.INSISTENT -> AlarmEscalation(intervalMs = 30_000L, maxRepeats = 3)
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
    /**
     * How much of the auto-silence window is left at this point in the chain.
     * Derived from [realertAttempt] rather than carried as a deadline extra, so
     * there is no absolute timestamp to go stale if the alarm fires late.
     */
    fun remainingRingMs(): Long {
        val escalation = escalationFor(style) ?: return 0L
        val elapsed = realertAttempt * escalation.intervalMs
        return (TimerAlarmReceiver.AUTO_SILENCE_MS - elapsed).coerceAtLeast(escalation.intervalMs)
    }

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

            return TimerAlarmRequest(
                messageId = messageId,
                chatId = chatId,
                caption = intent.getStringExtra(TimerAlarmScheduler.EXTRA_CAPTION),
                otherUserId = intent.getStringExtra(TimerAlarmScheduler.EXTRA_OTHER_USER_ID),
                style = resolveTimerAlarmStyle(
                    rawStyle = intent.getStringExtra(TimerAlarmScheduler.EXTRA_ALARM_STYLE),
                    legacySilent = intent.getBooleanExtra(TimerAlarmScheduler.EXTRA_SILENT, false),
                ),
                sound = resolveTimerAlarmSound(intent.getStringExtra(TimerAlarmScheduler.EXTRA_ALARM_SOUND)),
                realertAttempt = intent.getIntExtra(TimerAlarmScheduler.EXTRA_REALERT_ATTEMPT, 0),
            )
        }
    }
}
