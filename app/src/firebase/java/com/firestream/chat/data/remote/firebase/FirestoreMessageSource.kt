// region: AGENT-NOTE
// Responsibility: Firestore message subcollection I/O — chats/{chatId}/messages/{id}.
//   Reads emit RawMessage (ciphertext or plaintext); writes accept
//   pre-encrypted payloads. Per-key map updates for readBy / deliveredTo /
//   reactions via FieldValue dot-notation.
// Owns: Listener registrations on chats/{chatId}/messages — caller must close
//   the returned Flow to detach.
// Collaborators: MessageRepositoryImpl (only caller); decrypts via SignalManager
//   on the way out. Also reachable through the MessageSource interface in
//   data/remote/source/ so the pocketbase flavor can swap in its own impl.
// Don't put here: Signal encryption itself (SignalManager), Room caching
//   (MessageRepositoryImpl), unread-count increments (sendPushNotification
//   Cloud Function in functions/index.js).
// endregion

package com.firestream.chat.data.remote.firebase

import com.firestream.chat.data.remote.source.MessageSource
import com.firestream.chat.data.remote.source.RawMessage
import com.firestream.chat.domain.model.MessageStatus
import com.firestream.chat.domain.model.MessageType
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.ListenerRegistration
import com.google.firebase.firestore.Query
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.tasks.await
import javax.inject.Inject
import javax.inject.Singleton

private const val POLL_CONTENT = "📊 Poll"
private const val LIST_CONTENT = "📋 List"
private const val CALL_CONTENT = "📞 Voice call"

@Singleton
class FirestoreMessageSource @Inject constructor(
    private val firestore: FirebaseFirestore
) : MessageSource {
    /**
     * The preview string written to `chats/{id}.lastMessageContent` for a given
     * [type]. [plain] is the raw user content (text body, image caption, etc.);
     * it only matters for TEXT and IMAGE. Exposed so repositories can mirror
     * the same string into Room for optimistic local updates, avoiding a
     * preview flicker when the Firestore echo lands.
     */
    override fun lastContentFor(type: MessageType, plain: String): String = when (type) {
        MessageType.IMAGE -> if (plain.isNotBlank()) "📷 $plain" else "📷 Photo"
        MessageType.DOCUMENT -> "📎 File"
        MessageType.VOICE -> "🎤 Voice message"
        MessageType.POLL -> POLL_CONTENT
        MessageType.LIST -> LIST_CONTENT.takeIf { plain.isBlank() } ?: plain
        MessageType.LOCATION -> "📍 Location"
        MessageType.CALL -> CALL_CONTENT
        else -> plain.ifBlank { "Message" }
    }
    override fun observeMessages(chatId: String): Flow<List<RawMessage>> = callbackFlow {
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

    override suspend fun fetchMessages(chatId: String): List<RawMessage> {
        val snapshot = firestore
            .collection("chats").document(chatId)
            .collection("messages")
            .orderBy("timestamp", Query.Direction.ASCENDING)
            .get()
            .await()
        return snapshot.documents.mapNotNull { doc ->
            doc.data?.let { mapToRaw(doc.id, chatId, it) }
        }
    }

    override suspend fun sendMessage(
        chatId: String,
        senderId: String,
        ciphertext: String,
        signalType: Int,
        type: MessageType,
        replyToId: String?,
        timestamp: Long,
        mediaUrl: String?,
        isForwarded: Boolean,
        duration: Int?,
        mentions: List<String>,
        plainContent: String,
        emojiSizes: Map<Int, Float>,
        mediaWidth: Int?,
        mediaHeight: Int?,
        latitude: Double?,
        longitude: Double?,
        isHd: Boolean
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
            "duration" to duration,
            "mentions" to mentions,
            "emojiSizes" to emojiSizes.mapKeys { it.key.toString() }
        )
        if (mediaWidth != null) data["mediaWidth"] = mediaWidth
        if (mediaHeight != null) data["mediaHeight"] = mediaHeight
        if (latitude != null) data["latitude"] = latitude
        if (longitude != null) data["longitude"] = longitude
        if (isHd) data["isHd"] = true
        val docRef = firestore
            .collection("chats").document(chatId)
            .collection("messages")
            .add(data)
            .await()

        firestore.collection("chats").document(chatId).update(
            mapOf(
                "lastMessageContent" to lastContentFor(type, plainContent),
                "lastMessageTimestamp" to timestamp,
                "lastMessageSenderId" to senderId
            )
        ).await()

        return docRef.id
    }

    override suspend fun sendPlainMessage(
        chatId: String,
        senderId: String,
        content: String,
        type: MessageType,
        replyToId: String?,
        timestamp: Long,
        mediaUrl: String?,
        isForwarded: Boolean,
        duration: Int?,
        mentions: List<String>,
        emojiSizes: Map<Int, Float>,
        mediaWidth: Int?,
        mediaHeight: Int?,
        latitude: Double?,
        longitude: Double?,
        isHd: Boolean
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
            "duration" to duration,
            "mentions" to mentions,
            "emojiSizes" to emojiSizes.mapKeys { it.key.toString() }
        )
        if (mediaWidth != null) data["mediaWidth"] = mediaWidth
        if (mediaHeight != null) data["mediaHeight"] = mediaHeight
        if (latitude != null) data["latitude"] = latitude
        if (longitude != null) data["longitude"] = longitude
        if (isHd) data["isHd"] = true
        val docRef = firestore
            .collection("chats").document(chatId)
            .collection("messages")
            .add(data)
            .await()

        firestore.collection("chats").document(chatId).update(
            mapOf(
                "lastMessageContent" to lastContentFor(type, content),
                "lastMessageTimestamp" to timestamp,
                "lastMessageSenderId" to senderId
            )
        ).await()

        return docRef.id
    }

    override suspend fun editMessage(chatId: String, messageId: String, newContent: String, editedAt: Long) {
        firestore
            .collection("chats").document(chatId)
            .collection("messages").document(messageId)
            .update(mapOf("content" to newContent, "editedAt" to editedAt))
            .await()
    }

    override suspend fun deleteMessage(chatId: String, messageId: String) {
        firestore
            .collection("chats").document(chatId)
            .collection("messages").document(messageId)
            .update(mapOf(
                "deletedAt" to System.currentTimeMillis(),
                "content" to "",
                "ciphertext" to null,
                "mediaUrl" to null,
                "mediaThumbnailUrl" to null
            ))
            .await()
    }

    override suspend fun updateMessageStatus(chatId: String, messageId: String, status: String) {
        firestore
            .collection("chats").document(chatId)
            .collection("messages").document(messageId)
            .update("status", status)
            .await()
    }

    override suspend fun getUndeliveredMessageIds(chatId: String, currentUserId: String): List<String> {
        val snapshot = firestore.collection("chats").document(chatId)
            .collection("messages")
            .whereEqualTo("status", MessageStatus.SENT.name)
            .get()
            .await()
        return snapshot.documents
            .filter { doc -> (doc.getString("senderId") ?: "") != currentUserId }
            .map { it.id }
    }

    override suspend fun markDelivered(chatId: String, messageId: String, userId: String, timestamp: Long) {
        firestore.collection("chats").document(chatId)
            .collection("messages").document(messageId)
            .update(mapOf(
                "deliveredTo.$userId" to timestamp,
                "status" to MessageStatus.DELIVERED.name
            ))
            .await()
    }

    override suspend fun markRead(chatId: String, messageId: String, userId: String, timestamp: Long) {
        firestore.collection("chats").document(chatId)
            .collection("messages").document(messageId)
            .update(mapOf(
                "readBy.$userId" to timestamp,
                "status" to MessageStatus.READ.name
            ))
            .await()
    }

    override suspend fun sendPollMessage(
        chatId: String,
        senderId: String,
        pollData: Map<String, Any?>,
        timestamp: Long
    ): String {
        val data = hashMapOf(
            "senderId" to senderId,
            "content" to POLL_CONTENT,
            "type" to MessageType.POLL.name,
            "status" to MessageStatus.SENT.name,
            "timestamp" to timestamp,
            "reactions" to emptyMap<String, String>(),
            "isForwarded" to false,
            "pollData" to pollData
        )
        val docRef = firestore
            .collection("chats").document(chatId)
            .collection("messages")
            .add(data)
            .await()

        firestore.collection("chats").document(chatId).update(
            mapOf(
                "lastMessageContent" to POLL_CONTENT,
                "lastMessageTimestamp" to timestamp,
                "lastMessageSenderId" to senderId
            )
        ).await()

        return docRef.id
    }

    override suspend fun votePoll(
        chatId: String,
        messageId: String,
        userId: String,
        optionIds: List<String>
    ) {
        val docRef = firestore
            .collection("chats").document(chatId)
            .collection("messages").document(messageId)

        val snapshot = docRef.get().await()
        @Suppress("UNCHECKED_CAST")
        val rawPoll = snapshot.get("pollData") as? Map<String, Any?> ?: return
        @Suppress("UNCHECKED_CAST")
        val options = (rawPoll["options"] as? List<Map<String, Any?>>)?.toMutableList() ?: return
        val isMultipleChoice = rawPoll["isMultipleChoice"] as? Boolean ?: false

        val updatedOptions = options.map { option ->
            val optId = option["id"] as? String ?: return@map option
            @Suppress("UNCHECKED_CAST")
            val voters = (option["voterIds"] as? List<String>)?.toMutableList() ?: mutableListOf()

            if (optionIds.contains(optId)) {
                if (!voters.contains(userId)) voters.add(userId)
            } else if (!isMultipleChoice) {
                voters.remove(userId)
            }

            option.toMutableMap().apply { put("voterIds", voters) }
        }

        docRef.update("pollData.options", updatedOptions).await()
    }

    override suspend fun sendCallMessage(
        chatId: String,
        senderId: String,
        endReason: String,
        durationSeconds: Int,
        timestamp: Long
    ): String {
        val data = hashMapOf(
            "senderId" to senderId,
            "content" to endReason,
            "type" to MessageType.CALL.name,
            "status" to MessageStatus.SENT.name,
            "timestamp" to timestamp,
            "duration" to durationSeconds,
            "reactions" to emptyMap<String, String>(),
            "isForwarded" to false
        )
        val docRef = firestore
            .collection("chats").document(chatId)
            .collection("messages")
            .add(data)
            .await()
        firestore.collection("chats").document(chatId).update(
            mapOf(
                "lastMessageContent" to lastContentFor(MessageType.CALL),
                "lastMessageTimestamp" to timestamp,
                "lastMessageSenderId" to senderId
            )
        ).await()
        return docRef.id
    }

    override suspend fun sendListMessage(
        chatId: String,
        senderId: String,
        listId: String,
        content: String,
        timestamp: Long,
        listDiff: Map<String, Any?>?
    ): String {
        val data = hashMapOf(
            "senderId" to senderId,
            "content" to content,
            "type" to MessageType.LIST.name,
            "status" to MessageStatus.SENT.name,
            "timestamp" to timestamp,
            "reactions" to emptyMap<String, String>(),
            "isForwarded" to false,
            "listId" to listId
        )
        if (listDiff != null) data["listDiff"] = listDiff
        val docRef = firestore
            .collection("chats").document(chatId)
            .collection("messages")
            .add(data)
            .await()

        firestore.collection("chats").document(chatId).update(
            mapOf(
                "lastMessageContent" to content,
                "lastMessageTimestamp" to timestamp,
                "lastMessageSenderId" to senderId
            )
        ).await()

        return docRef.id
    }

    override suspend fun updateListMessageDiff(
        chatId: String,
        messageId: String,
        content: String,
        listDiff: Map<String, Any?>,
        timestamp: Long
    ) {
        firestore.collection("chats").document(chatId)
            .collection("messages").document(messageId)
            .update(mapOf(
                "content" to content,
                "listDiff" to listDiff,
                "timestamp" to timestamp,
                "editedAt" to timestamp
            )).await()

        firestore.collection("chats").document(chatId).update(
            mapOf(
                "lastMessageContent" to content,
                "lastMessageTimestamp" to timestamp
            )
        ).await()
    }

    override suspend fun pinMessage(chatId: String, messageId: String, pinned: Boolean) {
        firestore
            .collection("chats").document(chatId)
            .collection("messages").document(messageId)
            .update("isPinned", pinned)
            .await()
    }

    override suspend fun closePoll(chatId: String, messageId: String) {
        firestore
            .collection("chats").document(chatId)
            .collection("messages").document(messageId)
            .update("pollData.isClosed", true)
            .await()
    }

    override suspend fun updateReactions(chatId: String, messageId: String, reactions: Map<String, String>) {
        firestore
            .collection("chats").document(chatId)
            .collection("messages").document(messageId)
            .update("reactions", reactions)
            .await()
    }

    @Suppress("UNCHECKED_CAST")
    private fun mapToRaw(id: String, chatId: String, data: Map<String, Any?>): RawMessage {
        val rawReactions = (data["reactions"] as? Map<*, *>)
            ?.entries
            ?.mapNotNull { (k, v) ->
                val key = k as? String ?: return@mapNotNull null
                val value = v as? String ?: return@mapNotNull null
                key to value
            }
            ?.toMap() ?: emptyMap()

        return RawMessage(
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
            duration = (data["duration"] as? Long)?.toInt(),
            readBy = parseLongMap(data["readBy"]),
            deliveredTo = parseLongMap(data["deliveredTo"]),
            pollData = data["pollData"] as? Map<String, Any?>,
            mentions = (data["mentions"] as? List<*>)?.filterIsInstance<String>() ?: emptyList(),
            deletedAt = data["deletedAt"] as? Long,
            emojiSizes = parseIntFloatMap(data["emojiSizes"]),
            listId = data["listId"] as? String,
            listDiff = data["listDiff"] as? Map<String, Any?>,
            isPinned = data["isPinned"] as? Boolean ?: false,
            mediaWidth = (data["mediaWidth"] as? Long)?.toInt(),
            mediaHeight = (data["mediaHeight"] as? Long)?.toInt(),
            latitude = (data["latitude"] as? Number)?.toDouble(),
            longitude = (data["longitude"] as? Number)?.toDouble(),
            isHd = data["isHd"] as? Boolean ?: false
        )
    }

    private fun parseIntFloatMap(raw: Any?): Map<Int, Float> {
        return (raw as? Map<*, *>)
            ?.entries
            ?.mapNotNull { (k, v) ->
                val key = (k as? String)?.toIntOrNull() ?: (k as? Number)?.toInt() ?: return@mapNotNull null
                val value = (v as? Number)?.toFloat() ?: return@mapNotNull null
                key to value
            }
            ?.toMap() ?: emptyMap()
    }

    private fun parseLongMap(raw: Any?): Map<String, Long> {
        return (raw as? Map<*, *>)
            ?.entries
            ?.mapNotNull { (k, v) ->
                val key = k as? String ?: return@mapNotNull null
                val value = (v as? Long) ?: (v as? Number)?.toLong() ?: return@mapNotNull null
                key to value
            }
            ?.toMap() ?: emptyMap()
    }
}
