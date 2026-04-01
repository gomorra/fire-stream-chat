package com.firestream.chat.data.repository

import android.net.Uri
import com.firestream.chat.data.local.dao.ChatDao
import com.firestream.chat.data.local.dao.MessageDao
import com.firestream.chat.data.local.entity.ChatEntity
import com.firestream.chat.data.remote.firebase.FirebaseAuthSource
import com.firestream.chat.data.remote.firebase.FirebaseStorageSource
import com.firestream.chat.data.util.ProfileImageManager
import com.firestream.chat.domain.model.Chat
import com.firestream.chat.domain.model.ChatType
import com.firestream.chat.domain.model.GroupPermissions
import com.firestream.chat.domain.model.GroupRole
import com.firestream.chat.domain.model.Message
import com.firestream.chat.domain.repository.ChatRepository
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.ListenerRegistration
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ChatRepositoryImpl @Inject constructor(
    private val chatDao: ChatDao,
    private val messageDao: MessageDao,
    private val firestore: FirebaseFirestore,
    private val authSource: FirebaseAuthSource,
    private val storageSource: FirebaseStorageSource,
    private val profileImageManager: ProfileImageManager
) : ChatRepository {

    private fun observeRemoteChats(): Flow<List<Chat>> = callbackFlow {
        val uid = authSource.currentUserId ?: throw Exception("Not authenticated")
        val listener: ListenerRegistration = firestore
            .collection("chats")
            .whereArrayContains("participants", uid)
            .addSnapshotListener { snapshot, error ->
                if (error != null) {
                    // Don't close the flow on transient errors — just skip this emission.
                    // Closing would propagate an exception that kills getChats() entirely.
                    return@addSnapshotListener
                }
                val chats = snapshot?.documents?.mapNotNull { doc ->
                    doc.data?.let { mapToChat(doc.id, it, uid) }
                } ?: emptyList()
                trySend(chats)
            }
        awaitClose { listener.remove() }
    }

    override fun getChats(): Flow<List<Chat>> = channelFlow {
        // Sync remote chats to Room, preserving local-only fields.
        // Errors here must NOT propagate to the channelFlow scope — that would cancel the
        // Room collection and leave the UI with a permanently empty chat list.
        launch {
            try {
                observeRemoteChats().collectLatest { chats ->
                    val existingMap = chatDao.getChatsByIds(chats.map { it.id }).associateBy { it.id }
                    val entities = chats.map { chat ->
                        val existing = existingMap[chat.id]
                        val entity = ChatEntity.fromDomain(chat)
                        if (existing != null) {
                            entity.copy(
                                isPinned = existing.isPinned,
                                isArchived = existing.isArchived,
                                muteUntil = existing.muteUntil,
                                cachedAvatarUrl = existing.cachedAvatarUrl,
                                localAvatarPath = existing.localAvatarPath
                            )
                        } else entity
                    }
                    chatDao.insertChats(entities)

                    // Download avatars for group/broadcast chats whose URL changed
                    for (chat in chats.filter { it.type != ChatType.INDIVIDUAL }) {
                        val existing = existingMap[chat.id]
                        if (chat.avatarUrl != null) {
                            val needsDownload = chat.avatarUrl != existing?.cachedAvatarUrl ||
                                existing?.localAvatarPath == null ||
                                !profileImageManager.fileExists(chat.id)
                            if (needsDownload) {
                                try {
                                    val file = profileImageManager.downloadAvatar(chat.id, chat.avatarUrl)
                                    chatDao.updateAvatarCache(chat.id, chat.avatarUrl, file.absolutePath)
                                } catch (_: Exception) { }
                            }
                        } else if (existing?.localAvatarPath != null) {
                            profileImageManager.deleteAvatar(chat.id)
                            chatDao.updateAvatarCache(chat.id, null, null)
                        }
                    }
                }
            } catch (_: Exception) {
                // Sync failed (auth not ready, network error, etc.).
                // Room data is still served via getAllChats() below.
            }
        }
        // Emit from Room (which has local fields)
        chatDao.getAllChats()
            .map { entities -> entities.map { it.toDomain() } }
            .collect { send(it) }
    }

    override suspend fun getChatById(chatId: String): Result<Chat> {
        return try {
            val uid = authSource.currentUserId ?: ""
            val doc = firestore.collection("chats").document(chatId).get().await()
            if (doc.exists()) {
                Result.success(mapToChat(doc.id, doc.data!!, uid))
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
                Result.success(mapToChat(doc.id, doc.data!!, uid))
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
                "owner" to uid,
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
                owner = uid,
                createdAt = System.currentTimeMillis(),
                createdBy = uid
            )
            chatDao.insertChat(ChatEntity.fromDomain(chat))
            Result.success(chat)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    override suspend fun uploadGroupAvatar(chatId: String, uri: Uri): Result<String> {
        return try {
            val localFile = profileImageManager.saveLocalCopy(chatId, uri)
            val url = storageSource.uploadGroupAvatar(chatId, uri)
            updateGroup(chatId, name = null, avatarUrl = url).getOrThrow()
            chatDao.updateAvatarCache(chatId, url, localFile.absolutePath)
            Result.success(url)
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

    override suspend fun deleteChat(chatId: String): Result<Unit> {
        return try {
            val uid = authSource.currentUserId ?: throw Exception("Not authenticated")
            firestore.collection("chats").document(chatId)
                .update("participants", FieldValue.arrayRemove(uid))
                .await()
            messageDao.deleteMessagesByChatId(chatId)
            chatDao.deleteChat(chatId)
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

    override suspend fun pinChat(chatId: String, pinned: Boolean): Result<Unit> {
        return try {
            chatDao.setPinned(chatId, pinned)
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    override suspend fun archiveChat(chatId: String, archived: Boolean): Result<Unit> {
        return try {
            chatDao.setArchived(chatId, archived)
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    override suspend fun muteChat(chatId: String, muteUntil: Long): Result<Unit> {
        return try {
            chatDao.setMuteUntil(chatId, muteUntil)
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    override suspend fun updateGroupDescription(chatId: String, description: String): Result<Unit> {
        return try {
            firestore.collection("chats").document(chatId)
                .update("description", description)
                .await()
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    override suspend fun generateInviteLink(chatId: String): Result<String> {
        return try {
            val token = java.util.UUID.randomUUID().toString()
            val uid = authSource.currentUserId ?: throw Exception("Not authenticated")
            val batch = firestore.batch()
            batch.update(
                firestore.collection("chats").document(chatId),
                "inviteLink", token
            )
            batch.set(
                firestore.collection("inviteLinks").document(token),
                mapOf("chatId" to chatId, "createdAt" to System.currentTimeMillis(), "createdBy" to uid)
            )
            batch.commit().await()
            Result.success(token)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    override suspend fun revokeInviteLink(chatId: String): Result<Unit> {
        return try {
            val doc = firestore.collection("chats").document(chatId).get().await()
            val existingToken = doc.getString("inviteLink")
            val batch = firestore.batch()
            batch.update(
                firestore.collection("chats").document(chatId),
                "inviteLink", FieldValue.delete()
            )
            if (existingToken != null) {
                batch.delete(firestore.collection("inviteLinks").document(existingToken))
            }
            batch.commit().await()
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    override suspend fun joinGroupViaLink(inviteToken: String): Result<Chat> {
        return try {
            val uid = authSource.currentUserId ?: throw Exception("Not authenticated")
            val linkDoc = firestore.collection("inviteLinks").document(inviteToken).get().await()
            if (!linkDoc.exists()) throw Exception("Invalid or expired invite link")
            val chatId = linkDoc.getString("chatId") ?: throw Exception("Invalid invite link data")

            val chatDoc = firestore.collection("chats").document(chatId).get().await()
            if (!chatDoc.exists()) throw Exception("Group no longer exists")
            val chat = mapToChat(chatId, chatDoc.data!!, uid)

            if (uid in chat.participants) throw Exception("Already a member of this group")

            val requireApproval = chatDoc.getBoolean("requireApproval") ?: false
            if (requireApproval) {
                firestore.collection("chats").document(chatId)
                    .update("pendingMembers", FieldValue.arrayUnion(uid))
                    .await()
                Result.success(chat.copy(pendingMembers = chat.pendingMembers + uid))
            } else {
                firestore.collection("chats").document(chatId)
                    .update("participants", FieldValue.arrayUnion(uid))
                    .await()
                val updatedChat = chat.copy(participants = chat.participants + uid)
                chatDao.insertChat(ChatEntity.fromDomain(updatedChat))
                Result.success(updatedChat)
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    override suspend fun setRequireApproval(chatId: String, enabled: Boolean): Result<Unit> {
        return try {
            firestore.collection("chats").document(chatId)
                .update("requireApproval", enabled)
                .await()
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    override suspend fun approveMember(chatId: String, userId: String): Result<Unit> {
        return try {
            val batch = firestore.batch()
            val chatRef = firestore.collection("chats").document(chatId)
            batch.update(chatRef, "pendingMembers", FieldValue.arrayRemove(userId))
            batch.update(chatRef, "participants", FieldValue.arrayUnion(userId))
            batch.commit().await()
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    override suspend fun rejectMember(chatId: String, userId: String): Result<Unit> {
        return try {
            firestore.collection("chats").document(chatId)
                .update("pendingMembers", FieldValue.arrayRemove(userId))
                .await()
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    override suspend fun leaveGroup(chatId: String): Result<Unit> {
        return try {
            val uid = authSource.currentUserId ?: throw Exception("Not authenticated")
            val chatDoc = firestore.collection("chats").document(chatId).get().await()
            if (!chatDoc.exists()) throw Exception("Chat not found")
            val chat = mapToChat(chatId, chatDoc.data!!, uid)

            val isAdmin = uid in chat.admins
            val otherParticipants = chat.participants.filter { it != uid }

            if (otherParticipants.isEmpty()) {
                // Last member — delete the group
                firestore.collection("chats").document(chatId).delete().await()
            } else if (isAdmin && chat.admins.size == 1) {
                // Only admin leaving — promote first remaining participant atomically
                val newAdmin = otherParticipants.first()
                val batch = firestore.batch()
                val chatRef = firestore.collection("chats").document(chatId)
                batch.update(chatRef, "participants", FieldValue.arrayRemove(uid))
                batch.update(chatRef, "admins", FieldValue.arrayRemove(uid))
                batch.update(chatRef, "admins", FieldValue.arrayUnion(newAdmin))
                batch.commit().await()
            } else {
                firestore.collection("chats").document(chatId).update(
                    mapOf(
                        "participants" to FieldValue.arrayRemove(uid),
                        "admins" to FieldValue.arrayRemove(uid)
                    )
                ).await()
            }
            chatDao.deleteChat(chatId)
            messageDao.deleteMessagesByChatId(chatId)
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    override suspend fun updateGroupPermissions(chatId: String, permissions: GroupPermissions): Result<Unit> {
        return try {
            val permissionsMap = mapOf(
                "sendMessages" to permissions.sendMessages.name,
                "editGroupInfo" to permissions.editGroupInfo.name,
                "addMembers" to permissions.addMembers.name,
                "createPolls" to permissions.createPolls.name,
                "isAnnouncementMode" to permissions.isAnnouncementMode
            )
            firestore.collection("chats").document(chatId)
                .update("permissions", permissionsMap)
                .await()
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    override suspend fun promoteToAdmin(chatId: String, userId: String): Result<Unit> {
        return try {
            firestore.collection("chats").document(chatId)
                .update("admins", FieldValue.arrayUnion(userId))
                .await()
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    override suspend fun demoteFromAdmin(chatId: String, userId: String): Result<Unit> {
        return try {
            firestore.collection("chats").document(chatId)
                .update("admins", FieldValue.arrayRemove(userId))
                .await()
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    override suspend fun transferOwnership(chatId: String, newOwnerId: String): Result<Unit> {
        return try {
            val batch = firestore.batch()
            val chatRef = firestore.collection("chats").document(chatId)
            batch.update(chatRef, "owner", newOwnerId)
            batch.update(chatRef, "admins", FieldValue.arrayUnion(newOwnerId))
            batch.commit().await()
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    override suspend fun createBroadcastList(name: String, recipientIds: List<String>): Result<Chat> {
        return try {
            val uid = authSource.currentUserId ?: throw Exception("Not authenticated")
            val allParticipants = (recipientIds + uid).distinct()

            val chatData = hashMapOf(
                "type" to ChatType.BROADCAST.name,
                "name" to name,
                "participants" to allParticipants,
                "createdAt" to System.currentTimeMillis(),
                "createdBy" to uid
            )
            val docRef = firestore.collection("chats").add(chatData).await()
            val chat = Chat(
                id = docRef.id,
                type = ChatType.BROADCAST,
                name = name,
                participants = allParticipants,
                createdAt = System.currentTimeMillis(),
                createdBy = uid
            )
            chatDao.insertChat(ChatEntity.fromDomain(chat))
            Result.success(chat)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    override suspend fun resetUnreadCount(chatId: String): Result<Unit> {
        return try {
            val uid = authSource.currentUserId ?: throw Exception("Not authenticated")
            chatDao.updateUnreadCount(chatId, 0)
            firestore.collection("chats").document(chatId)
                .update("unreadCounts.$uid", 0)
                .await()
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    private fun mapToGroupPermissions(raw: Any?): GroupPermissions {
        val data = raw as? Map<*, *> ?: return GroupPermissions()
        return GroupPermissions(
            sendMessages = runCatching { GroupRole.valueOf(data["sendMessages"] as String) }.getOrDefault(GroupRole.MEMBER),
            editGroupInfo = runCatching { GroupRole.valueOf(data["editGroupInfo"] as String) }.getOrDefault(GroupRole.ADMIN),
            addMembers = runCatching { GroupRole.valueOf(data["addMembers"] as String) }.getOrDefault(GroupRole.ADMIN),
            createPolls = runCatching { GroupRole.valueOf(data["createPolls"] as String) }.getOrDefault(GroupRole.MEMBER),
            isAnnouncementMode = data["isAnnouncementMode"] as? Boolean ?: false
        )
    }

    private fun mapToChat(id: String, data: Map<String, Any?>, currentUserId: String): Chat {
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

        val perUserUnread = (data["unreadCounts"] as? Map<*, *>)
            ?.get(currentUserId) as? Long

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
            unreadCount = perUserUnread?.toInt() ?: (data["unreadCount"] as? Long)?.toInt() ?: 0,
            createdAt = data["createdAt"] as? Long ?: 0L,
            createdBy = data["createdBy"] as? String,
            admins = (data["admins"] as? List<*>)?.filterIsInstance<String>() ?: emptyList(),
            typingUserIds = typingUserIds,
            description = data["description"] as? String,
            inviteLink = data["inviteLink"] as? String,
            requireApproval = data["requireApproval"] as? Boolean ?: false,
            pendingMembers = (data["pendingMembers"] as? List<*>)?.filterIsInstance<String>() ?: emptyList(),
            owner = data["owner"] as? String,
            permissions = mapToGroupPermissions(data["permissions"])
        )
    }
}
