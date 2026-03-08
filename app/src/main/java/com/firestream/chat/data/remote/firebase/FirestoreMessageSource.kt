package com.firestream.chat.data.remote.firebase

import com.firestream.chat.domain.model.MessageStatus
import com.firestream.chat.domain.model.MessageType
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.ListenerRegistration
import com.google.firebase.firestore.Query
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.tasks.await
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Raw Firestore message before decryption.
 * [ciphertext] and [signalType] are non-null for encrypted messages.
 * [content] is non-null for group/plaintext messages.
 */
data class RawFirestoreMessage(
    val id: String,
    val chatId: String,
    val senderId: String,
    val content: String?,
    val ciphertext: String?,
    val signalType: Int?,
    val type: String,
    val mediaUrl: String?,
    val mediaThumbnailUrl: String?,
    val status: String,
    val replyToId: String?,
    val timestamp: Long,
    val editedAt: Long?,
    // Phase 1
    val reactions: Map<String, String> = emptyMap(),
    val isForwarded: Boolean = false,
    val duration: Int? = null
)

@Singleton
class FirestoreMessageSource @Inject constructor(
    private val firestore: FirebaseFirestore
) {
    fun observeMessages(chatId: String): Flow<List<RawFirestoreMessage>> = callbackFlow {
        val listener: ListenerRegistration = firestore
            .collection("chats").document(chatId)
            .collection("messages")
            .orderBy("timestamp", Query.Direction.ASCENDING)
            .addSnapshotListener { snapshot, error ->
                if (error != null) {
                    close(error)
                    return@addSnapshotListener
                }
                val messages = snapshot?.documents?.mapNotNull { doc ->
                    doc.data?.let { mapToRaw(doc.id, chatId, it) }
                } ?: emptyList()
                trySend(messages)
            }
        awaitClose { listener.remove() }
    }

    suspend fun sendMessage(
        chatId: String,
        senderId: String,
        ciphertext: String,
        signalType: Int,
        type: MessageType,
        replyToId: String?,
        timestamp: Long,
        mediaUrl: String? = null,
        isForwarded: Boolean = false,
        duration: Int? = null
    ): String {
        val data = hashMapOf(
            "senderId" to senderId,
            "ciphertext" to ciphertext,
            "signalType" to signalType,
            "type" to type.name,
            "status" to MessageStatus.SENT.name,
            "replyToId" to replyToId,
            "timestamp" to timestamp,
            "mediaUrl" to mediaUrl,
            "reactions" to emptyMap<String, String>(),
            "isForwarded" to isForwarded,
            "duration" to duration
        )
        val docRef = firestore
            .collection("chats").document(chatId)
            .collection("messages")
            .add(data)
            .await()

        val lastContent = when (type) {
            MessageType.IMAGE -> "📷 Photo"
            MessageType.DOCUMENT -> "📎 File"
            MessageType.VOICE -> "🎤 Voice message"
            else -> "New message"
        }
        firestore.collection("chats").document(chatId).update(
            mapOf(
                "lastMessageContent" to lastContent,
                "lastMessageTimestamp" to timestamp,
                "lastMessageSenderId" to senderId
            )
        ).await()

        return docRef.id
    }

    suspend fun sendPlainMessage(
        chatId: String,
        senderId: String,
        content: String,
        type: MessageType,
        replyToId: String?,
        timestamp: Long,
        mediaUrl: String? = null,
        isForwarded: Boolean = false,
        duration: Int? = null
    ): String {
        val data = hashMapOf(
            "senderId" to senderId,
            "content" to content,
            "type" to type.name,
            "status" to MessageStatus.SENT.name,
            "replyToId" to replyToId,
            "timestamp" to timestamp,
            "mediaUrl" to mediaUrl,
            "reactions" to emptyMap<String, String>(),
            "isForwarded" to isForwarded,
            "duration" to duration
        )
        val docRef = firestore
            .collection("chats").document(chatId)
            .collection("messages")
            .add(data)
            .await()

        val lastContent = when (type) {
            MessageType.IMAGE -> "📷 Photo"
            MessageType.DOCUMENT -> "📎 File"
            MessageType.VOICE -> "🎤 Voice message"
            else -> content
        }
        firestore.collection("chats").document(chatId).update(
            mapOf(
                "lastMessageContent" to lastContent,
                "lastMessageTimestamp" to timestamp,
                "lastMessageSenderId" to senderId
            )
        ).await()

        return docRef.id
    }

    suspend fun editMessage(chatId: String, messageId: String, newContent: String, editedAt: Long) {
        firestore
            .collection("chats").document(chatId)
            .collection("messages").document(messageId)
            .update(mapOf("content" to newContent, "editedAt" to editedAt))
            .await()
    }

    suspend fun deleteMessage(chatId: String, messageId: String) {
        firestore
            .collection("chats").document(chatId)
            .collection("messages").document(messageId)
            .delete()
            .await()
    }

    suspend fun updateMessageStatus(chatId: String, messageId: String, status: String) {
        firestore
            .collection("chats").document(chatId)
            .collection("messages").document(messageId)
            .update("status", status)
            .await()
    }

    suspend fun updateReactions(chatId: String, messageId: String, reactions: Map<String, String>) {
        firestore
            .collection("chats").document(chatId)
            .collection("messages").document(messageId)
            .update("reactions", reactions)
            .await()
    }

    @Suppress("UNCHECKED_CAST")
    private fun mapToRaw(id: String, chatId: String, data: Map<String, Any?>): RawFirestoreMessage {
        val rawReactions = (data["reactions"] as? Map<*, *>)
            ?.entries
            ?.mapNotNull { (k, v) ->
                val key = k as? String ?: return@mapNotNull null
                val value = v as? String ?: return@mapNotNull null
                key to value
            }
            ?.toMap() ?: emptyMap()

        return RawFirestoreMessage(
            id = id,
            chatId = chatId,
            senderId = data["senderId"] as? String ?: "",
            content = data["content"] as? String,
            ciphertext = data["ciphertext"] as? String,
            signalType = (data["signalType"] as? Long)?.toInt(),
            type = data["type"] as? String ?: MessageType.TEXT.name,
            mediaUrl = data["mediaUrl"] as? String,
            mediaThumbnailUrl = data["mediaThumbnailUrl"] as? String,
            status = data["status"] as? String ?: MessageStatus.SENT.name,
            replyToId = data["replyToId"] as? String,
            timestamp = data["timestamp"] as? Long ?: 0L,
            editedAt = data["editedAt"] as? Long,
            reactions = rawReactions,
            isForwarded = data["isForwarded"] as? Boolean ?: false,
            duration = (data["duration"] as? Long)?.toInt()
        )
    }
}
