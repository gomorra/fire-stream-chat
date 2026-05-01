// region: AGENT-NOTE
// Responsibility: Chat lifecycle — create/observe/update chats, group metadata,
//   admin operations, archive/pin/mute, group avatar upload. Local-first: writes
//   to ChatDao, observes Firestore, merges into Room.
// Owns: ChatEntity rows + group-management Firestore writes.
// Collaborators: ChatDao, MessageDao (lastMessage propagation), FirestoreChatSource,
//   FirebaseAuthSource (current user), FirebaseStorageSource (group avatars),
//   ProfileImageManager.
// Don't put here: message CRUD (MessageRepositoryImpl), per-user profile
//   (UserRepositoryImpl), Signal session creation (SignalManager).
// endregion

package com.firestream.chat.data.repository

import android.net.Uri
import com.firestream.chat.data.local.dao.ChatDao
import com.firestream.chat.data.local.dao.MessageDao
import com.firestream.chat.data.local.entity.ChatEntity
import com.firestream.chat.data.remote.source.AuthSource
import com.firestream.chat.data.remote.source.StorageSource
import com.firestream.chat.data.remote.source.ChatSource
import com.firestream.chat.data.util.ProfileImageManager
import com.firestream.chat.data.util.resultOf
import com.firestream.chat.domain.model.Chat
import com.firestream.chat.domain.model.ChatType
import com.firestream.chat.domain.model.GroupPermissions
import com.firestream.chat.domain.repository.ChatRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ChatRepositoryImpl @Inject constructor(
    private val chatDao: ChatDao,
    private val messageDao: MessageDao,
    private val chatSource: ChatSource,
    private val authSource: AuthSource,
    private val storageSource: StorageSource,
    private val profileImageManager: ProfileImageManager
) : ChatRepository {

    override fun getChats(): Flow<List<Chat>> = channelFlow {
        // Sync remote chats to Room, preserving local-only fields.
        // Errors here must NOT propagate to the channelFlow scope — that would cancel the
        // Room collection and leave the UI with a permanently empty chat list.
        launch {
            try {
                val uid = authSource.currentUserId ?: throw Exception("Not authenticated")
                chatSource.observeChatsForUser(uid).collectLatest { chats ->
                    // Atomic read+merge+write inside Room — see ChatDao.upsertRemote KDoc.
                    val existingMap = chatDao.upsertRemote(chats.map { ChatEntity.fromDomain(it) })

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

    override suspend fun getChatById(chatId: String): Result<Chat> = resultOf {
        val uid = authSource.currentUserId ?: ""
        chatSource.getChat(chatId, uid) ?: throw Exception("Chat not found")
    }

    override suspend fun getOrCreateChat(participantId: String): Result<Chat> = resultOf {
        val uid = authSource.currentUserId ?: throw Exception("Not authenticated")
        val participants = listOf(uid, participantId).sorted()

        val existing = chatSource.findIndividualChat(participants, uid)
        if (existing != null) {
            existing
        } else {
            val chatData = hashMapOf<String, Any?>(
                "type" to ChatType.INDIVIDUAL.name,
                "participants" to participants,
                "createdAt" to System.currentTimeMillis(),
                "createdBy" to uid
            )
            val docId = chatSource.createChat(chatData)
            val chat = Chat(
                id = docId,
                type = ChatType.INDIVIDUAL,
                participants = participants,
                createdAt = System.currentTimeMillis(),
                createdBy = uid
            )
            chatDao.insertChat(ChatEntity.fromDomain(chat))
            chat
        }
    }

    override suspend fun createGroup(name: String, participantIds: List<String>): Result<Chat> = resultOf {
        val uid = authSource.currentUserId ?: throw Exception("Not authenticated")
        val allParticipants = (participantIds + uid).distinct()

        val chatData = hashMapOf<String, Any?>(
            "type" to ChatType.GROUP.name,
            "name" to name,
            "participants" to allParticipants,
            "admins" to listOf(uid),
            "owner" to uid,
            "createdAt" to System.currentTimeMillis(),
            "createdBy" to uid
        )
        val docId = chatSource.createChat(chatData)
        val chat = Chat(
            id = docId,
            type = ChatType.GROUP,
            name = name,
            participants = allParticipants,
            admins = listOf(uid),
            owner = uid,
            createdAt = System.currentTimeMillis(),
            createdBy = uid
        )
        chatDao.insertChat(ChatEntity.fromDomain(chat))
        chat
    }

    override suspend fun uploadGroupAvatar(chatId: String, uri: String): Result<String> = resultOf {
        val parsedUri = Uri.parse(uri)
        val localFile = profileImageManager.saveLocalCopy(chatId, parsedUri)
        val url = storageSource.uploadGroupAvatar(chatId, parsedUri)
        updateGroup(chatId, name = null, avatarUrl = url).getOrThrow()
        chatDao.updateAvatarCache(chatId, url, localFile.absolutePath)
        url
    }

    override suspend fun updateGroup(chatId: String, name: String?, avatarUrl: String?): Result<Unit> = resultOf {
        val updates = mutableMapOf<String, Any?>()
        name?.let { updates["name"] = it }
        avatarUrl?.let { updates["avatarUrl"] = it }
        chatSource.updateChatFields(chatId, updates)
    }

    override suspend fun addGroupMember(chatId: String, userId: String): Result<Unit> = resultOf {
        chatSource.addToArrayField(chatId, "participants", userId)
    }

    override suspend fun removeGroupMember(chatId: String, userId: String): Result<Unit> = resultOf {
        chatSource.removeFromArrayField(chatId, "participants", userId)
    }

    override suspend fun deleteChat(chatId: String): Result<Unit> = resultOf {
        val uid = authSource.currentUserId ?: throw Exception("Not authenticated")
        chatSource.removeFromArrayField(chatId, "participants", uid)
        messageDao.deleteMessagesByChatId(chatId)
        chatDao.deleteChat(chatId)
    }

    override fun observeTyping(chatId: String): Flow<List<String>> =
        chatSource.observeTypingUsers(chatId)

    override suspend fun setTyping(chatId: String, isTyping: Boolean) {
        val uid = authSource.currentUserId ?: return
        try {
            chatSource.setTyping(chatId, uid, isTyping)
        } catch (_: Exception) {
            // Typing updates are best-effort; ignore errors
        }
    }

    override suspend fun pinChat(chatId: String, pinned: Boolean): Result<Unit> = resultOf {
        chatDao.setPinned(chatId, pinned)
    }

    override suspend fun archiveChat(chatId: String, archived: Boolean): Result<Unit> = resultOf {
        chatDao.setArchived(chatId, archived)
    }

    override suspend fun muteChat(chatId: String, muteUntil: Long): Result<Unit> = resultOf {
        chatDao.setMuteUntil(chatId, muteUntil)
    }

    override suspend fun updateGroupDescription(chatId: String, description: String): Result<Unit> = resultOf {
        chatSource.updateChatFields(chatId, mapOf("description" to description))
    }

    override suspend fun generateInviteLink(chatId: String): Result<String> = resultOf {
        val uid = authSource.currentUserId ?: throw Exception("Not authenticated")
        val token = java.util.UUID.randomUUID().toString()
        chatSource.setInviteLink(chatId, token, createdBy = uid)
        token
    }

    override suspend fun revokeInviteLink(chatId: String): Result<Unit> = resultOf {
        val existingToken = chatSource.getExistingInviteToken(chatId)
        chatSource.clearInviteLink(chatId, existingToken)
    }

    override suspend fun joinGroupViaLink(inviteToken: String): Result<Chat> = resultOf {
        val uid = authSource.currentUserId ?: throw Exception("Not authenticated")
        val chatId = chatSource.getChatIdForInviteToken(inviteToken)
            ?: throw Exception("Invalid or expired invite link")

        val chat = chatSource.getChat(chatId, uid) ?: throw Exception("Group no longer exists")
        if (uid in chat.participants) throw Exception("Already a member of this group")

        if (chat.requireApproval) {
            chatSource.addToArrayField(chatId, "pendingMembers", uid)
            chat.copy(pendingMembers = chat.pendingMembers + uid)
        } else {
            chatSource.addToArrayField(chatId, "participants", uid)
            val updatedChat = chat.copy(participants = chat.participants + uid)
            chatDao.insertChat(ChatEntity.fromDomain(updatedChat))
            updatedChat
        }
    }

    override suspend fun setRequireApproval(chatId: String, enabled: Boolean): Result<Unit> = resultOf {
        chatSource.updateChatFields(chatId, mapOf("requireApproval" to enabled))
    }

    override suspend fun approveMember(chatId: String, userId: String): Result<Unit> = resultOf {
        chatSource.approveMember(chatId, userId)
    }

    override suspend fun rejectMember(chatId: String, userId: String): Result<Unit> = resultOf {
        chatSource.removeFromArrayField(chatId, "pendingMembers", userId)
    }

    override suspend fun leaveGroup(chatId: String): Result<Unit> = resultOf {
        val uid = authSource.currentUserId ?: throw Exception("Not authenticated")
        val chat = chatSource.getChat(chatId, uid) ?: throw Exception("Chat not found")

        val isAdmin = uid in chat.admins
        val otherParticipants = chat.participants.filter { it != uid }

        when {
            otherParticipants.isEmpty() -> {
                // Last member — delete the group
                chatSource.deleteChatDocument(chatId)
            }
            isAdmin && chat.admins.size == 1 -> {
                // Only admin leaving — promote first remaining participant atomically
                chatSource.leaveGroupPromotingAdmin(
                    chatId = chatId,
                    leavingUid = uid,
                    newAdminUid = otherParticipants.first()
                )
            }
            else -> {
                chatSource.leaveGroupRemovingSelf(chatId, uid)
            }
        }
        chatDao.deleteChat(chatId)
        messageDao.deleteMessagesByChatId(chatId)
    }

    override suspend fun updateGroupPermissions(chatId: String, permissions: GroupPermissions): Result<Unit> = resultOf {
        val permissionsMap = mapOf(
            "sendMessages" to permissions.sendMessages.name,
            "editGroupInfo" to permissions.editGroupInfo.name,
            "addMembers" to permissions.addMembers.name,
            "createPolls" to permissions.createPolls.name,
            "isAnnouncementMode" to permissions.isAnnouncementMode
        )
        chatSource.updateChatFields(chatId, mapOf("permissions" to permissionsMap))
    }

    override suspend fun promoteToAdmin(chatId: String, userId: String): Result<Unit> = resultOf {
        chatSource.addToArrayField(chatId, "admins", userId)
    }

    override suspend fun demoteFromAdmin(chatId: String, userId: String): Result<Unit> = resultOf {
        chatSource.removeFromArrayField(chatId, "admins", userId)
    }

    override suspend fun transferOwnership(chatId: String, newOwnerId: String): Result<Unit> = resultOf {
        chatSource.transferOwnership(chatId, newOwnerId)
    }

    override suspend fun createBroadcastList(name: String, recipientIds: List<String>): Result<Chat> = resultOf {
        val uid = authSource.currentUserId ?: throw Exception("Not authenticated")
        val allParticipants = (recipientIds + uid).distinct()

        val chatData = hashMapOf<String, Any?>(
            "type" to ChatType.BROADCAST.name,
            "name" to name,
            "participants" to allParticipants,
            "createdAt" to System.currentTimeMillis(),
            "createdBy" to uid
        )
        val docId = chatSource.createChat(chatData)
        val chat = Chat(
            id = docId,
            type = ChatType.BROADCAST,
            name = name,
            participants = allParticipants,
            createdAt = System.currentTimeMillis(),
            createdBy = uid
        )
        chatDao.insertChat(ChatEntity.fromDomain(chat))
        chat
    }

    override suspend fun resetUnreadCount(chatId: String): Result<Unit> = resultOf {
        val uid = authSource.currentUserId ?: throw Exception("Not authenticated")
        chatDao.updateUnreadCount(chatId, 0)
        chatSource.resetUnreadCount(chatId, uid)
    }
}
