package com.firestream.chat.data.remote.fcm

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import androidx.core.app.NotificationCompat
import com.firestream.chat.MainActivity
import com.firestream.chat.R
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import dagger.hilt.android.AndroidEntryPoint

@AndroidEntryPoint
class FCMService : FirebaseMessagingService() {

    override fun onNewToken(token: String) {
        super.onNewToken(token)
        // Token will be updated via AuthRepository when user is logged in
    }

    override fun onMessageReceived(message: RemoteMessage) {
        super.onMessageReceived(message)

        val data = message.data
        val senderId = data["senderId"] ?: return
        val senderName = data["senderName"] ?: "New Message"
        val chatId = data["chatId"] ?: return

        // In E2E encryption, we show a generic notification
        // The actual decryption happens when the app opens
        showNotification(chatId, senderName, "New message")
    }

    private fun showNotification(chatId: String, title: String, body: String) {
        val channelId = "fire_stream_messages"

        val notificationManager = getSystemService(NotificationManager::class.java)
        val channel = NotificationChannel(
            channelId,
            "Messages",
            NotificationManager.IMPORTANCE_HIGH
        )
        notificationManager.createNotificationChannel(channel)

        val intent = Intent(this, MainActivity::class.java).apply {
            putExtra("chatId", chatId)
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
        }
        val pendingIntent = PendingIntent.getActivity(
            this, chatId.hashCode(), intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notification = NotificationCompat.Builder(this, channelId)
            .setSmallIcon(android.R.drawable.ic_dialog_email)
            .setContentTitle(title)
            .setContentText(body)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setContentIntent(pendingIntent)
            .setAutoCancel(true)
            .build()

        notificationManager.notify(chatId.hashCode(), notification)
    }
}
