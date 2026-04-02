package com.firestream.chat.data.remote.fcm

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationCompat.MessagingStyle
import androidx.core.app.Person
import com.firestream.chat.MainActivity
import com.firestream.chat.R
import com.firestream.chat.data.call.CallService
import com.firestream.chat.data.call.CallStateHolder
import com.firestream.chat.data.local.PreferencesDataStore
import com.firestream.chat.domain.model.CallState
import com.firestream.chat.domain.model.ChatType
import com.firestream.chat.domain.model.MessageType
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
    @Inject lateinit var callStateHolder: CallStateHolder

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

        // Handle incoming call push
        if (data["type"] == "incoming_call") {
            handleIncomingCall(data)
            return
        }

        val senderId = data["senderId"] ?: return
        val senderName = data["senderName"] ?: "New Message"
        val chatId = data["chatId"] ?: return
        val messageId = data["messageId"]
        val chatType = data["chatType"] ?: "INDIVIDUAL"
        val chatName = data["chatName"]?.takeIf { it.isNotBlank() }
        val mentions = data["mentions"]?.split(",")?.filter { it.isNotBlank() } ?: emptyList()
        val messageType = data["messageType"] ?: "TEXT"

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

            val messageContent = data["messageContent"]
            showNotification(chatId, senderId, senderName, chatName, chatType == ChatType.GROUP.name, messageType, messageContent)
        }
    }

    private fun handleIncomingCall(data: Map<String, String>) {
        val callId = data["callId"] ?: return
        val callerId = data["callerId"] ?: return
        val callerName = data["callerName"] ?: "Unknown"
        val callerAvatarUrl = data["callerAvatarUrl"]

        // Don't start if already in a call
        val currentState = callStateHolder.callState.value
        if (currentState !is CallState.Idle && currentState !is CallState.Ended) return

        CallService.startIncoming(this, callId, callerId, callerName, callerAvatarUrl)
    }

    private fun showNotification(
        chatId: String,
        senderId: String,
        senderName: String,
        chatName: String?,
        isGroup: Boolean,
        messageType: String = "TEXT",
        messageContent: String? = null
    ) {
        val channelId = "fire_stream_messages"
        val notifId = chatId.hashCode()
        val notificationManager = getSystemService(NotificationManager::class.java)

        notificationManager.createNotificationChannel(
            NotificationChannel(channelId, "Messages", NotificationManager.IMPORTANCE_HIGH)
        )

        val me = Person.Builder().setName("Me").build()
        val sender = Person.Builder().setName(senderName).build()

        // Recover existing MessagingStyle so messages from the same chat bundle together
        val existing = notificationManager.activeNotifications.find { it.id == notifId }
        val style = existing?.let { MessagingStyle.extractMessagingStyleFromNotification(it.notification) }
            ?: MessagingStyle(me)
        if (isGroup) {
            style.setGroupConversation(true).setConversationTitle(chatName ?: chatId)
        }
        val parsedType = runCatching { MessageType.valueOf(messageType) }.getOrNull()
        val notificationText = when (parsedType) {
            MessageType.LIST -> messageContent?.takeIf { it.isNotBlank() } ?: "\uD83D\uDCCB Shared a list"
            MessageType.IMAGE -> "\uD83D\uDCF7 Photo"
            MessageType.VIDEO -> "\uD83C\uDFA5 Video"
            MessageType.VOICE -> "\uD83C\uDF99\uFE0F Voice message"
            MessageType.DOCUMENT -> "\uD83D\uDCCE Document"
            MessageType.POLL -> "\uD83D\uDCCA Poll"
            else -> "New message"
        }
        style.addMessage(notificationText, System.currentTimeMillis(), sender)

        val intent = Intent(this, MainActivity::class.java).apply {
            putExtra("chatId", chatId)
            putExtra("senderId", senderId)
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
        }
        val pendingIntent = PendingIntent.getActivity(
            this, notifId, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notification = NotificationCompat.Builder(this, channelId)
            .setSmallIcon(android.R.drawable.ic_dialog_email)
            .setStyle(style)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setContentIntent(pendingIntent)
            .setAutoCancel(true)
            .build()

        notificationManager.notify(notifId, notification)
    }
}
