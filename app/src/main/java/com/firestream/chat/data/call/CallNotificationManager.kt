package com.firestream.chat.data.call

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.provider.Settings
import androidx.core.app.NotificationCompat
import com.firestream.chat.ui.call.CallActivity

class CallNotificationManager(private val context: Context) {

    companion object {
        const val CHANNEL_CALL = "fire_stream_calls"

        /**
         * Rings with the user's ringtone. Android freezes a channel's sound when the channel is
         * created, so the silent channel that came before it is deleted rather than edited.
         */
        const val CHANNEL_INCOMING_CALL = "fire_stream_incoming_calls_ringing"
        private const val CHANNEL_INCOMING_CALL_SILENT = "fire_stream_incoming_calls"

        const val NOTIFICATION_ID_ONGOING = 9001
        const val NOTIFICATION_ID_INCOMING = 9002
        const val NOTIFICATION_ID_RING_FALLBACK = 9003

        private val RING_VIBRATION_PATTERN = longArrayOf(0, 1000, 1000)
    }

    private val notifManager = context.getSystemService(NotificationManager::class.java)

    init {
        createChannels()
    }

    private fun createChannels() {
        val manager = notifManager

        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_CALL,
                "Ongoing Calls",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Notification for active voice calls"
            }
        )

        manager.deleteNotificationChannel(CHANNEL_INCOMING_CALL_SILENT)
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_INCOMING_CALL,
                "Incoming Calls",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "Incoming call alerts"
                // The ringtone stream, so the ring volume and the ringer mode apply.
                setSound(
                    Settings.System.DEFAULT_RINGTONE_URI,
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_NOTIFICATION_RINGTONE)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                        .build()
                )
                enableVibration(true)
                vibrationPattern = RING_VIBRATION_PATTERN
            }
        )
    }

    fun buildOngoingCallNotification(remoteName: String): Notification {
        val hangupIntent = Intent(context, CallService::class.java).apply {
            action = CallService.ACTION_HANGUP
        }
        val hangupPending = PendingIntent.getService(
            context, 0, hangupIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val launchIntent = buildCallActivityIntent()
        val launchPending = PendingIntent.getActivity(
            context, 0, launchIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(context, CHANNEL_CALL)
            .setSmallIcon(android.R.drawable.ic_menu_call)
            .setContentTitle("Voice Call")
            .setContentText("In call with $remoteName")
            .setOngoing(true)
            .setContentIntent(launchPending)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "Hang Up", hangupPending)
            .setCategory(NotificationCompat.CATEGORY_CALL)
            .build()
    }

    fun buildOutgoingCallNotification(remoteName: String): Notification {
        val hangupIntent = Intent(context, CallService::class.java).apply {
            action = CallService.ACTION_HANGUP
        }
        val hangupPending = PendingIntent.getService(
            context, 0, hangupIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val launchIntent = buildCallActivityIntent()
        val launchPending = PendingIntent.getActivity(
            context, 0, launchIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(context, CHANNEL_CALL)
            .setSmallIcon(android.R.drawable.ic_menu_call)
            .setContentTitle("Voice Call")
            .setContentText("Calling $remoteName...")
            .setOngoing(true)
            .setContentIntent(launchPending)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "Cancel", hangupPending)
            .setCategory(NotificationCompat.CATEGORY_CALL)
            .build()
    }

    fun buildIncomingCallNotification(callerName: String): Notification {
        val fullScreenIntent = buildCallActivityIntent()
        val fullScreenPending = PendingIntent.getActivity(
            context, 1, fullScreenIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        // Route Answer through CallActivity so RECORD_AUDIO permission can be requested
        val answerIntent = buildCallActivityIntent().apply {
            putExtra(CallActivity.EXTRA_ACTION, CallActivity.ACTION_ANSWER)
        }
        val answerPending = PendingIntent.getActivity(
            context, 2, answerIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        // Rings until the call is answered, declined or over. CallService then replaces this
        // notification with a silent one, or removes it, and the system stops the ring with it.
        // Its ring timeout ends an unanswered call after 30 s, which bounds the ring.
        // setTimeoutAfter would not: the system never times out a foreground service's
        // notification.
        return ringing(callerName, fullScreenPending)
            .addAction(android.R.drawable.ic_menu_call, "Answer", answerPending)
            .addAction(declineAction(requestCode = 3, callId = null))
            .setOngoing(true)
            .setAutoCancel(false)
            .build()
            .insistent()
    }

    /**
     * The ring for an incoming call whose push Android would not let start the call service.
     * Tapping it, or its full-screen intent, opens the call screen, which starts the service from
     * the foreground and rings as usual. Its Decline declines the call it names. It rings like the
     * service's own notification, and the system removes it when the call would have stopped
     * ringing, because no service holds it.
     */
    fun buildIncomingCallFallbackNotification(
        callId: String,
        callerId: String,
        callerName: String,
        callerAvatarUrl: String?
    ): Notification {
        val ringIntent = buildCallActivityIntent().apply {
            putExtra(CallActivity.EXTRA_ACTION, CallActivity.ACTION_RING)
            putExtra(CallActivity.EXTRA_CALL_ID, callId)
            putExtra(CallActivity.EXTRA_CALLER_ID, callerId)
            putExtra(CallActivity.EXTRA_CALLER_NAME, callerName)
            putExtra(CallActivity.EXTRA_CALLER_AVATAR_URL, callerAvatarUrl)
        }
        val ringPending = PendingIntent.getActivity(
            context, 4, ringIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return ringing(callerName, ringPending)
            .setContentIntent(ringPending)
            .addAction(declineAction(requestCode = 5, callId = callId))
            .setAutoCancel(true)
            .setTimeoutAfter(CallSession.RING_TIMEOUT_MS)
            .build()
            .insistent()
    }

    /**
     * What both incoming-call notifications share: the ringing channel, and the call screen at
     * full screen.
     */
    private fun ringing(callerName: String, fullScreen: PendingIntent): NotificationCompat.Builder =
        NotificationCompat.Builder(context, CHANNEL_INCOMING_CALL)
            .setSmallIcon(android.R.drawable.ic_menu_call)
            .setContentTitle("Incoming Voice Call")
            .setContentText(callerName)
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setCategory(NotificationCompat.CATEGORY_CALL)
            .setFullScreenIntent(fullScreen, true)

    /**
     * Decline, sent to [CallService]. A null [callId] declines the call the service holds. A call
     * id also reaches a call the service does not hold.
     */
    private fun declineAction(requestCode: Int, callId: String?): NotificationCompat.Action {
        val intent = Intent(context, CallService::class.java).apply {
            action = CallService.ACTION_DECLINE
            callId?.let { putExtra(CallService.EXTRA_CALL_ID, it) }
        }
        val pending = PendingIntent.getService(
            context, requestCode, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Action(android.R.drawable.ic_menu_close_clear_cancel, "Decline", pending)
    }

    /** Repeat the ringtone and the vibration until the notification goes. */
    private fun Notification.insistent(): Notification = apply { flags = flags or Notification.FLAG_INSISTENT }

    fun updateNotification(notification: Notification, id: Int = NOTIFICATION_ID_ONGOING) {
        notifManager.notify(id, notification)
    }

    fun cancelNotification(id: Int) {
        notifManager.cancel(id)
    }

    private fun buildCallActivityIntent(): Intent {
        return Intent().apply {
            setClassName(context.packageName, "com.firestream.chat.ui.call.CallActivity")
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
    }
}
