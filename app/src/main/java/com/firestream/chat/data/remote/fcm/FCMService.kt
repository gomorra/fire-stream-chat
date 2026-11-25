package com.firestream.chat.data.remote.fcm

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationCompat.MessagingStyle
import androidx.core.app.Person
import com.firestream.chat.MainActivity
import com.firestream.chat.R
import com.firestream.chat.data.call.CallNotificationManager
import com.firestream.chat.data.call.CallService
import com.firestream.chat.data.call.CallStateHolder
import com.firestream.chat.data.local.PreferencesDataStore
import com.firestream.chat.data.repository.DECRYPT_ERROR_PLACEHOLDER
import com.firestream.chat.domain.model.CallLogType
import com.firestream.chat.domain.model.ChatType
import com.firestream.chat.domain.model.Message
import com.firestream.chat.domain.model.MessageType
import com.firestream.chat.domain.model.PushReconcileResult
import com.firestream.chat.domain.model.isOngoing
import com.firestream.chat.domain.repository.AuthRepository
import com.firestream.chat.domain.repository.ChatRepository
import com.firestream.chat.domain.repository.MessageRepository
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import javax.inject.Inject

@AndroidEntryPoint
class FCMService : FirebaseMessagingService() {

    @Inject lateinit var authRepository: AuthRepository
    @Inject lateinit var chatRepository: ChatRepository
    @Inject lateinit var messageRepository: MessageRepository
    @Inject lateinit var preferencesDataStore: PreferencesDataStore
    @Inject lateinit var callStateHolder: CallStateHolder
    @Inject lateinit var activeChatTracker: ActiveChatTracker

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private companion object {
        const val TAG = "FCMService"
        const val PUSH_RECONCILE_TIMEOUT_MS = 4_000L
    }

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

        // Handle reaction push
        if (data["type"] == "reaction") {
            handleReaction(data)
            return
        }

        val senderId = data["senderId"] ?: return
        val senderName = data["senderName"] ?: "New Message"
        val chatId = data["chatId"] ?: return
        val messageId = data["messageId"]

        // Suppress notification if the user is already viewing this chat
        // in the foreground — its live listener already reconciles the message.
        if (activeChatTracker.isActive(chatId)) {
            if (messageId != null) {
                serviceScope.launch {
                    messageRepository.markMessagesAsDelivered(chatId, listOf(messageId))
                }
            }
            return
        }

        // Start reconcile independently so a notification-wait timeout never
        // cancels the background fetch, decrypt, or media auto-download.
        val reconcileDeferred = if (messageId != null) {
            serviceScope.async {
                messageRepository.reconcileFromPush(chatId, messageId)
            }
        } else {
            null
        }
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

        // A call message records a call. Only a missed one is news: the callee saw the others ring.
        val callText = if (messageType == MessageType.CALL.name) {
            callPushNotificationText(data) ?: return
        } else {
            null
        }

        // Await the bounded notification pipeline inside onMessageReceived so FCM's
        // wakelock stays held until showNotification() runs (preventing Doze /
        // cached-app freezing from dropping alerts). reconcileDeferred lives on
        // serviceScope so a 4s timeout still lets background fetch/decrypt finish.
        runBlocking(Dispatchers.IO) {
            // For group chats: if mention-only notifications are enabled,
            // suppress notification unless the current user is mentioned
            if (chatType == ChatType.GROUP.name) {
                val mentionOnly = preferencesDataStore.mentionOnlyNotificationsFlow.first()
                if (mentionOnly) {
                    val currentUserId = authRepository.currentUserId ?: return@runBlocking
                    val isMentioned = mentions.contains(currentUserId) || mentions.contains("everyone")
                    if (!isMentioned) return@runBlocking
                }
            }

            // Suppress notification for muted chats
            val chat = chatRepository.getChatById(chatId).getOrNull()
            if (chat != null) {
                val muteUntil = chat.muteUntil
                if (muteUntil == Long.MAX_VALUE || (muteUntil > 0 && muteUntil > System.currentTimeMillis())) {
                    return@runBlocking
                }
            }

            val reconcileResult = if (reconcileDeferred != null && callText == null) {
                withTimeoutOrNull(PUSH_RECONCILE_TIMEOUT_MS) { reconcileDeferred.await() }
                    ?: PushReconcileResult.Unavailable
            } else {
                PushReconcileResult.Unavailable
            }

            val reconciled = when (reconcileResult) {
                // Blocked sender or chat became active while reconciling — suppress notification.
                PushReconcileResult.Suppressed -> return@runBlocking
                is PushReconcileResult.Reconciled -> {
                    // Message was deleted before the push arrived — suppress notification.
                    if (reconcileResult.message.deletedAt != null) return@runBlocking
                    reconcileResult.message
                }
                PushReconcileResult.Unavailable -> null
            }

            // Re-check after awaiting reconcile: the user may have opened the chat meanwhile.
            if (activeChatTracker.isActive(chatId)) return@runBlocking

            val messageContent = data["messageContent"]
            showNotification(
                chatId = chatId,
                senderId = senderId,
                senderName = senderName,
                chatName = chatName,
                isGroup = chatType == ChatType.GROUP.name,
                messageType = messageType,
                messageContent = messageContent,
                reconciled = reconciled,
                overrideText = callText,
                messageId = messageId,
            )
        }
    }

    private fun handleReaction(data: Map<String, String>) {
        val chatId = data["chatId"] ?: return
        val senderId = data["senderId"] ?: return
        val emoji = data["emoji"] ?: return
        val senderName = data["senderName"] ?: "Someone"
        val messageAuthorId = data["messageAuthorId"]
        val chatType = data["chatType"] ?: "INDIVIDUAL"
        val chatName = data["chatName"]?.takeIf { it.isNotBlank() }
        // The reacted message's id — carried into the tap intent so opening the
        // notification scrolls to and highlights that bubble (not just the chat).
        val messageId = data["messageId"]?.takeIf { it.isNotBlank() }

        // The user is watching this chat — they see the reaction appear live.
        if (activeChatTracker.isActive(chatId)) return

        // Reactions are not new messages: no markMessagesAsDelivered here,
        // it would rewrite the status of an already-delivered message.
        serviceScope.launch {
            // Suppress notification for muted chats. The mention-only filter
            // does not apply: group reaction pushes only target the message
            // author, which is personal, not group noise.
            val chat = chatRepository.getChatById(chatId).getOrNull()
            if (chat != null) {
                val muteUntil = chat.muteUntil
                if (muteUntil == Long.MAX_VALUE || (muteUntil > 0 && muteUntil > System.currentTimeMillis())) {
                    return@launch
                }
            }

            val target = if (!messageAuthorId.isNullOrBlank() && messageAuthorId == authRepository.currentUserId) {
                "your message"
            } else {
                "a message"
            }
            showNotification(
                chatId = chatId,
                senderId = senderId,
                senderName = senderName,
                chatName = chatName,
                isGroup = chatType == ChatType.GROUP.name,
                overrideText = "Reacted $emoji to $target",
                messageId = messageId
            )
        }
    }

    private fun handleIncomingCall(data: Map<String, String>) {
        val callId = data["callId"] ?: return
        val callerId = data["callerId"] ?: return
        val callerName = data["callerName"] ?: "Unknown"
        val callerAvatarUrl = data["callerAvatarUrl"]
        // A function deployed before the kind existed sends none. The service then takes it
        // from the call document.
        val video = data["video"] == "true"

        // Don't start if already in a call
        if (callStateHolder.callState.value.isOngoing) return

        try {
            CallService.startIncoming(this, callId, callerId, callerName, callerAvatarUrl, video)
        } catch (e: IllegalStateException) {
            // Android 12+ lets a push start a foreground service only if it arrived at high
            // priority, and FCM can lower an app's priority. Ring with a notification instead:
            // opening it starts the service from the foreground.
            Log.w(TAG, "Could not start the call service for call $callId; ringing with a notification", e)
            val notifications = CallNotificationManager(this)
            notifications.updateNotification(
                notifications.buildIncomingCallFallbackNotification(callId, callerId, callerName, callerAvatarUrl, video),
                CallNotificationManager.NOTIFICATION_ID_RING_FALLBACK
            )
        }
    }

    private fun showNotification(
        chatId: String,
        senderId: String,
        senderName: String,
        chatName: String?,
        isGroup: Boolean,
        messageType: String = "TEXT",
        messageContent: String? = null,
        reconciled: Message? = null,
        overrideText: String? = null,
        // Target message id carried into the tap intent so the chat scrolls to
        // and highlights it: the latest message for message pushes, the reacted
        // message for reaction pushes. Null when there is no specific target.
        messageId: String? = null
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
        val notificationText = notificationPreviewText(
            messageType = messageType,
            messageContent = messageContent,
            reconciled = reconciled,
            overrideText = overrideText,
        )
        style.addMessage(notificationText, System.currentTimeMillis(), sender)

        val intent = Intent(this, MainActivity::class.java).apply {
            putExtra(MainActivity.EXTRA_CHAT_ID, chatId)
            putExtra(MainActivity.EXTRA_SENDER_ID, notificationPartnerHint(isGroup, senderId))
            // MessagingStyle bundles per chat and the PendingIntent is rebuilt with
            // FLAG_UPDATE_CURRENT on every push, so the extra always reflects the
            // latest message — tap lands on and highlights the newest message.
            if (messageId != null) putExtra(MainActivity.EXTRA_MESSAGE_ID, messageId)
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

/**
 * The chat partner a message notification's tap intent names: the sender in a
 * 1:1 chat, `""` in a group. A group has no partner, and naming the sender would
 * open the group with one member as its 1:1 partner. The extra is always written,
 * because `MainActivity.deepLinkFromIntent` ignores an intent without it.
 */
internal fun notificationPartnerHint(isGroup: Boolean, senderId: String): String =
    if (isGroup) "" else senderId

/**
 * Formats the notification line for a pushed message, preferring locally
 * reconciled (decrypted) content from Room when the FCM data payload omitted
 * `messageContent` (as release builds do for privacy). Never shows the
 * decrypt-failure placeholder in a notification.
 */
internal fun notificationPreviewText(
    messageType: String,
    messageContent: String?,
    reconciled: Message?,
    overrideText: String? = null,
): String {
    if (overrideText != null) return overrideText
    if (reconciled?.deletedAt != null) return "New message"
    val resolvedType = reconciled?.type ?: runCatching { MessageType.valueOf(messageType) }.getOrNull()
    val body = reconciled?.content
        ?.takeIf { it.isNotBlank() && it != DECRYPT_ERROR_PLACEHOLDER }
        ?: messageContent?.takeIf { it.isNotBlank() }
    return when (resolvedType) {
        MessageType.TEXT -> body ?: "New message"
        MessageType.LIST -> body ?: "\uD83D\uDCCB Shared a list"
        MessageType.IMAGE -> if (body != null) "\uD83D\uDCF7 $body" else "\uD83D\uDCF7 Photo"
        MessageType.VIDEO -> if (body != null) "\uD83C\uDFA5 $body" else "\uD83C\uDFA5 Video"
        MessageType.VOICE -> "\uD83C\uDF99\uFE0F Voice message"
        MessageType.DOCUMENT -> if (body != null) "\uD83D\uDCCE $body" else "\uD83D\uDCCE Document"
        MessageType.LOCATION -> if (body != null) "\uD83D\uDCCD $body" else "\uD83D\uDCCD Location"
        MessageType.POLL -> "\uD83D\uDCCA Poll"
        MessageType.STICKER -> "Sticker"
        MessageType.GIF -> if (body != null) "\uD83C\uDF9E\uFE0F $body" else "\uD83C\uDF9E\uFE0F GIF"
        else -> body ?: "New message"
    }
}

/**
 * The notification text for a pushed call message, or null when the call needs no notification.
 * Only the caller writes a call message, so the recipient is the callee. A call that never connected
 * and was not declined is a missed call. The callee saw any other call ring. A push from a Cloud
 * Function that does not send `callDurationSeconds` cannot tell the two apart, and says "Call".
 */
internal fun callPushNotificationText(data: Map<String, String>): String? {
    val durationSeconds = data["callDurationSeconds"]?.toIntOrNull() ?: return "\uD83D\uDCDE Call"
    val type = CallLogType.of(isOwnMessage = false, endReason = data["messageContent"].orEmpty(), durationSeconds = durationSeconds)
    return if (type == CallLogType.MISSED) "\uD83D\uDCDE Missed call" else null
}
