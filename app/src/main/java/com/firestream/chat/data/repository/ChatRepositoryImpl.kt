package com.firestream.chat.data.repository

import com.firestream.chat.data.local.dao.ChatDao
import com.firestream.chat.data.local.entity.ChatEntity
import com.firestream.chat.data.remote.firebase.FirebaseAuthSource
import com.firestream.chat.domain.model.Chat
import com.firestream.chat.domain.model.ChatType
import com.firestream.chat.domain.model.Message
import com.firestream.chat.domain.repository.ChatRepository
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.ListenerRegistration
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.tasks.await
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ChatRepositoryImpl @Inject constructor(
    private val chatDao: ChatDao,
    private val firestore: FirebaseFirestore,
    private val authSource: FirebaseAuthSource
) : ChatRepository {

    override fun getChats(): Flow<List<Chat>> = callbackFlow {
        val uid = authSource.currentUserId ?: throw Exception("Not authenticated")
        val listener: ListenerRegistration = firestore
            .collection("chats")
            .whereArrayContains("participants", uid)
            .addSnapshotListener { snapshot, error ->
                if (error != null) {
                    close(error)
                    return@addSnapshotListener
                }
                val chats = snapshot?.documents?.mapNotNull { doc ->
                    doc.data?.let { mapToChat(doc.id, it) }
                } ?: emptyList()
                trySend(chats)
            }
        awaitClose { listener.remove() }
    }.onEach { chats ->
        chatDao.insertChats(chats.map { ChatEntity.fromDomain(it) })
    }

    override suspend fun getChatById(chatId: String): Result<Chat> {
        return try {
            val doc = firestore.collection("chats").document(chatId).get().await()
            if (doc.exists()) {
                Result.success(mapToChat(doc.id, doc.data!!))
            } else {
                Result.failure(Exception("Chat not found"))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    override suspend fun getOrCreateChat(participantId: String): Result<Chat> {
        return try {
            val uid = authSource.currentUserId ?: throw Exception("Not authenticated")
            val participants = listOf(uid, participantId).sorted()

            val existing = firestore.collection("chats")
                .whereEqualTo("type", ChatType.INDIVIDUAL.name)
                .whereEqualTo("participants", participants)
                .get().await()

            if (!existing.isEmpty) {
                val doc = existing.documents.first()
                Result.success(mapToChat(doc.id, doc.data!!))
            } else {
                val chatData = hashMapOf(
                    "type" to ChatType.INDIVIDUAL.name,
                    "participants" to participants,
                    "createdAt" to System.currentTimeMillis(),
                    "createdBy" to uid
                )
                val docRef = firestore.collection("chats").add(chatData).await()
                val chat = Chat(
                    id = docRef.id,
                    type = ChatType.INDIVIDUAL,
                    participants = participants,
                    createdAt = System.currentTimeMillis(),
                    createdBy = uid
                )
                chatDao.insertChat(ChatEntity.fromDomain(chat))
                Result.success(chat)
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    override suspend fun createGroup(name: String, participantIds: List<String>): Result<Chat> {
        return try {
            val uid = authSource.currentUserId ?: throw Exception("Not authenticated")
            val allParticipants = (participantIds + uid).distinct()

            val chatData = hashMapOf(
                "type" to ChatType.GROUP.name,
                "name" to name,
                "participants" to allParticipants,
                "admins" to listOf(uid),
                "createdAt" to System.currentTimeMillis(),
                "createdBy" to uid
            )
            val docRef = firestore.collection("chats").add(chatData).await()
            val chat = Chat(
                id = docRef.id,
                type = ChatType.GROUP,
                name = name,
                participants = allParticipants,
                admins = listOf(uid),
                createdAt = System.currentTimeMillis(),
                createdBy = uid
            )
            chatDao.insertChat(ChatEntity.fromDomain(chat))
            Result.success(chat)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    override suspend fun updateGroup(chatId: String, name: String?, avatarUrl: String?): Result<Unit> {
        return try {
            val updates = mutableMapOf<String, Any>()
            name?.let { updates["name"] = it }
            avatarUrl?.let { updates["avatarUrl"] = it }
            if (updates.isNotEmpty()) {
                firestore.collection("chats").document(chatId).update(updates).await()
            }
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    override suspend fun addGroupMember(chatId: String, userId: String): Result<Unit> {
        return try {
            firestore.collection("chats").document(chatId)
                .update("participants", FieldValue.arrayUnion(userId))
                .await()
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    override suspend fun removeGroupMember(chatId: String, userId: String): Result<Unit> {
        return try {
            firestore.collection("chats").document(chatId)
                .update("participants", FieldValue.arrayRemove(userId))
                .await()
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    override fun observeTyping(chatId: String): Flow<List<String>> = callbackFlow {
        val listener: ListenerRegistration = firestore
            .collection("chats").document(chatId)
            .addSnapshotListener { snapshot, error ->
                if (error != null) {
                    close(error)
                    return@addSnapshotListener
                }
                val typingUsers = (snapshot?.get("typingUsers") as? Map<*, *>)
                    ?.entries
                    ?.filter { (_, v) ->
                        val ts = v as? Long ?: return@filter false
                        System.currentTimeMillis() - ts < 10_000
                    }
                    ?.mapNotNull { it.key as? String }
                    ?: emptyList()
                trySend(typingUsers)
            }
        awaitClose { listener.remove() }
    }

    override suspend fun setTyping(chatId: String, isTyping: Boolean) {
        val uid = authSource.currentUserId ?: return
        try {
            val update = if (isTyping) {
                mapOf("typingUsers.$uid" to System.currentTimeMillis())
            } else {
                mapOf("typingUsers.$uid" to FieldValue.delete())
            }
            firestore.collection("chats").document(chatId).update(update).await()
        } catch (_: Exception) {
            // Typing updates are best-effort; ignore errors
        }
    }

    private fun mapToChat(id: String, data: Map<String, Any?>): Chat {
        val lastMessageContent = data["lastMessageContent"] as? String
        val lastMessageTimestamp = data["lastMessageTimestamp"] as? Long
        val lastMessageSenderId = data["lastMessageSenderId"] as? String

        val lastMessage = if (lastMessageContent != null && lastMessageTimestamp != null && lastMessageSenderId != null) {
            Message(
                id = "",
                chatId = id,
                senderId = lastMessageSenderId,
                content = lastMessageContent,
                timestamp = lastMessageTimestamp
            )
        } else null

        val typingUserIds = (data["typingUsers"] as? Map<*, *>)
            ?.entries
            ?.filter { (_, v) ->
                val ts = v as? Long ?: return@filter false
                System.currentTimeMillis() - ts < 10_000
            }
            ?.mapNotNull { it.key as? String }
            ?: emptyList()

        return Chat(
            id = id,
            type = try {
                ChatType.valueOf(data["type"] as? String ?: "INDIVIDUAL")
            } catch (_: Exception) {
                ChatType.INDIVIDUAL
            },
            name = data["name"] as? String,
            avatarUrl = data["avatarUrl"] as? String,
            participants = (data["participants"] as? List<*>)?.filterIsInstance<String>() ?: emptyList(),
            lastMessage = lastMessage,
            unreadCount = (data["unreadCount"] as? Long)?.toInt() ?: 0,
            createdAt = data["createdAt"] as? Long ?: 0L,
            createdBy = data["createdBy"] as? String,
            admins = (data["admins"] as? List<*>)?.filterIsInstance<String>() ?: emptyList(),
            typingUserIds = typingUserIds
        )
    }
}
