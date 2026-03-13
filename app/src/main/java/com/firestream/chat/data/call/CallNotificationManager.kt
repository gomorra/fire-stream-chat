package com.firestream.chat.data.call

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import com.firestream.chat.ui.call.CallActivity

class CallNotificationManager(private val context: Context) {

    companion object {
        const val CHANNEL_CALL = "fire_stream_calls"
        const val CHANNEL_INCOMING_CALL = "fire_stream_incoming_calls"
        const val NOTIFICATION_ID_ONGOING = 9001
        const val NOTIFICATION_ID_INCOMING = 9002
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

        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_INCOMING_CALL,
                "Incoming Calls",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "Incoming call alerts"
                setSound(null, null)
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

        val declineIntent = Intent(context, CallService::class.java).apply {
            action = CallService.ACTION_DECLINE
        }
        val declinePending = PendingIntent.getService(
            context, 3, declineIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(context, CHANNEL_INCOMING_CALL)
            .setSmallIcon(android.R.drawable.ic_menu_call)
            .setContentTitle("Incoming Voice Call")
            .setContentText(callerName)
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setCategory(NotificationCompat.CATEGORY_CALL)
            .setFullScreenIntent(fullScreenPending, true)
            .addAction(android.R.drawable.ic_menu_call, "Answer", answerPending)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "Decline", declinePending)
            .setOngoing(true)
            .setAutoCancel(false)
            .build()
    }

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
