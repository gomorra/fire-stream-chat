package com.firestream.chat.data.remote.firebase

import com.firestream.chat.domain.model.Message
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

@Singleton
class FirestoreMessageSource @Inject constructor(
    private val firestore: FirebaseFirestore
) {
    fun observeMessages(chatId: String): Flow<List<Message>> = callbackFlow {
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
                    doc.data?.let { mapToMessage(doc.id, chatId, it) }
                } ?: emptyList()
                trySend(messages)
            }
        awaitClose { listener.remove() }
    }

    suspend fun sendMessage(chatId: String, message: Message): String {
        val messageData = hashMapOf(
            "senderId" to message.senderId,
            "content" to message.content,
            "type" to message.type.name,
            "mediaUrl" to message.mediaUrl,
            "mediaThumbnailUrl" to message.mediaThumbnailUrl,
            "status" to MessageStatus.SENT.name,
            "replyToId" to message.replyToId,
            "timestamp" to message.timestamp
        )

        val docRef = firestore
            .collection("chats").document(chatId)
            .collection("messages")
            .add(messageData)
            .await()

        // Update chat's last message
        firestore.collection("chats").document(chatId).update(
            mapOf(
                "lastMessageContent" to message.content,
                "lastMessageSenderId" to message.senderId,
                "lastMessageTimestamp" to message.timestamp
            )
        ).await()

        return docRef.id
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

    private fun mapToMessage(id: String, chatId: String, data: Map<String, Any?>): Message {
        return Message(
            id = id,
            chatId = chatId,
            senderId = data["senderId"] as? String ?: "",
            content = data["content"] as? String ?: "",
            type = try {
                MessageType.valueOf(data["type"] as? String ?: "TEXT")
            } catch (_: Exception) {
                MessageType.TEXT
            },
            mediaUrl = data["mediaUrl"] as? String,
            mediaThumbnailUrl = data["mediaThumbnailUrl"] as? String,
            status = try {
                MessageStatus.valueOf(data["status"] as? String ?: "SENT")
            } catch (_: Exception) {
                MessageStatus.SENT
            },
            replyToId = data["replyToId"] as? String,
            timestamp = data["timestamp"] as? Long ?: 0L,
            editedAt = data["editedAt"] as? Long
        )
    }
}
