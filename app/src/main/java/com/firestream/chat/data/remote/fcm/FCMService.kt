package com.firestream.chat.data.remote.fcm

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import androidx.core.app.NotificationCompat
import com.firestream.chat.MainActivity
import com.firestream.chat.R
import com.firestream.chat.data.local.PreferencesDataStore
import com.firestream.chat.domain.model.ChatType
import com.firestream.chat.domain.repository.AuthRepository
import com.firestream.chat.domain.repository.MessageRepository
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import javax.inject.Inject

@AndroidEntryPoint
class FCMService : FirebaseMessagingService() {

    @Inject lateinit var authRepository: AuthRepository
    @Inject lateinit var messageRepository: MessageRepository
    @Inject lateinit var preferencesDataStore: PreferencesDataStore

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onNewToken(token: String) {
        super.onNewToken(token)
        if (authRepository.isLoggedIn) {
            serviceScope.launch {
                authRepository.updateFcmToken(token)
            }
        }
    }

    override fun onMessageReceived(message: RemoteMessage) {
        super.onMessageReceived(message)

        val data = message.data
        val senderId = data["senderId"] ?: return
        val senderName = data["senderName"] ?: "New Message"
        val chatId = data["chatId"] ?: return
        val messageId = data["messageId"]
        val chatType = data["chatType"] ?: "INDIVIDUAL"
        val mentions = data["mentions"]?.split(",")?.filter { it.isNotBlank() } ?: emptyList()

        // Mark message as delivered when push notification is received
        if (messageId != null) {
            serviceScope.launch {
                messageRepository.markMessagesAsDelivered(chatId, listOf(messageId))
            }
        }

        serviceScope.launch {
            // For group chats: if mention-only notifications are enabled,
            // suppress notification unless the current user is mentioned
            if (chatType == ChatType.GROUP.name) {
                val mentionOnly = preferencesDataStore.mentionOnlyNotificationsFlow.first()
                if (mentionOnly) {
                    val currentUserId = authRepository.currentUserId ?: return@launch
                    val isMentioned = mentions.contains(currentUserId) || mentions.contains("everyone")
                    if (!isMentioned) return@launch
                }
            }

            showNotification(chatId, senderId, senderName, "New message")
        }
    }

    private fun showNotification(chatId: String, senderId: String, title: String, body: String) {
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
            putExtra("senderId", senderId)
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
