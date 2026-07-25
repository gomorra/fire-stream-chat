package com.firestream.chat.data.timer

import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import com.firestream.chat.MainActivity
import com.firestream.chat.R
import com.firestream.chat.di.ApplicationScope
import com.firestream.chat.domain.repository.MessageRepository
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Fires when an [TimerAlarmScheduler]-armed alarm reaches its trigger time.
 *
 * Two side effects, both must complete before the receiver returns:
 *  1. Flip the message's `timerState` to `COMPLETED` via [MessageRepository] —
 *     this propagates to Firestore + Room so both devices' bubbles update.
 *  2. Post the alarm-style notification on the [TimerNotificationChannel].
 *
 * `goAsync()` extends the receiver's lifetime to ~10s so the suspend call has
 * a chance to land before Android tears the process back down.
 */
@AndroidEntryPoint
class TimerAlarmReceiver : BroadcastReceiver() {

    @Inject
    lateinit var messageRepository: MessageRepository

    @Inject
    @ApplicationScope
    lateinit var appScope: CoroutineScope

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != TimerAlarmScheduler.ACTION_TIMER_FIRED) return

        val messageId = intent.getStringExtra(TimerAlarmScheduler.EXTRA_MESSAGE_ID) ?: return
        val chatId = intent.getStringExtra(TimerAlarmScheduler.EXTRA_CHAT_ID) ?: return
        val caption = intent.getStringExtra(TimerAlarmScheduler.EXTRA_CAPTION)
        val otherUserId = intent.getStringExtra(TimerAlarmScheduler.EXTRA_OTHER_USER_ID)
        val silent = intent.getBooleanExtra(TimerAlarmScheduler.EXTRA_SILENT, false)

        // Post the notification synchronously — it's the user-visible primary
        // effect and we don't want to lose it if the suspend call below stalls.
        // Silent timers skip the notification but still flip to COMPLETED.
        if (!silent) {
            postAlarmNotification(context, messageId, chatId, otherUserId, caption)
        }

        // The state flip is best-effort: if the network is down, the next
        // observer reconciliation on either device will catch up because the
        // local Room entry is also marked completed by markTimerCompleted.
        val pendingResult = goAsync()
        appScope.launch {
            try {
                messageRepository.markTimerCompleted(chatId, messageId)
            } finally {
                pendingResult.finish()
            }
        }
    }

    private fun postAlarmNotification(
        context: Context,
        messageId: String,
        chatId: String,
        otherUserId: String?,
        caption: String?,
    ) {
        val openChatIntent = buildOpenChatIntent(context, messageId, chatId, otherUserId)
        val openChatPending = PendingIntent.getActivity(
            context,
            messageId.hashCode(),
            openChatIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        val title = if (caption.isNullOrBlank()) "Timer ended" else caption
        val notification = NotificationCompat.Builder(context, TimerNotificationChannel.CHANNEL_ID)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle(title)
            .setContentText("Tap to open the chat")
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setAutoCancel(true)
            .setContentIntent(openChatPending)
            .setFullScreenIntent(openChatPending, true)
            .build()

        val nm = context.getSystemService(NotificationManager::class.java) ?: return
        nm.notify(NOTIFICATION_TAG, messageId.hashCode(), notification)
    }

    companion object {
        private const val NOTIFICATION_TAG: String = "timer_alarm"

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
