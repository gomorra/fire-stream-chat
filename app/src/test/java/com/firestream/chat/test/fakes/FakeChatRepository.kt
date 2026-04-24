package com.firestream.chat.test.fakes

import com.firestream.chat.domain.model.Chat
import com.firestream.chat.domain.model.GroupPermissions
import com.firestream.chat.domain.repository.ChatRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.map

internal class FakeChatRepository : ChatRepository {

    private val _chats = MutableStateFlow<List<Chat>>(emptyList())
    private val _typing = MutableStateFlow<Map<String, List<String>>>(emptyMap())

    var nextFailure: Throwable? = null

    val resetUnreadCalls: MutableList<String> = mutableListOf()
    val archivedIds: MutableList<Pair<String, Boolean>> = mutableListOf()
    val pinnedIds: MutableList<Pair<String, Boolean>> = mutableListOf()
    val mutedIds: MutableList<Pair<String, Long>> = mutableListOf()
    val deletedIds: MutableList<String> = mutableListOf()

    /**
     * When set, [getChatById] returns this for every call (ignoring the id lookup).
     * Unset it to fall back to scanning [_chats].
     */
    var chatByIdResult: Result<Chat>? = null

    fun emit(list: List<Chat>) {
        _chats.value = list
    }

    fun reset() {
        _chats.value = emptyList()
        _typing.value = emptyMap()
        nextFailure = null
        chatByIdResult = null
        resetUnreadCalls.clear()
        archivedIds.clear()
        pinnedIds.clear()
        mutedIds.clear()
        deletedIds.clear()
    }

    private fun consumeFailure(): Result<Nothing>? =
        nextFailure?.also { nextFailure = null }?.let { Result.failure(it) }

    // ── Core flows ────────────────────────────────────────────────────────────

    override fun getChats(): Flow<List<Chat>> = _chats.asStateFlow()

    override suspend fun getChatById(chatId: String): Result<Chat> {
        consumeFailure()?.let { return it }
        return chatByIdResult
            ?: _chats.value.find { it.id == chatId }
                ?.let { Result.success(it) }
            ?: Result.failure(NoSuchElementException(chatId))
    }

    override fun observeTyping(chatId: String): Flow<List<String>> =
        _typing.map { it[chatId].orEmpty() }

    override suspend fun setTyping(chatId: String, isTyping: Boolean) = Unit

    // ── Unread ────────────────────────────────────────────────────────────────

    override suspend fun resetUnreadCount(chatId: String): Result<Unit> {
        consumeFailure()?.let { return it }
        resetUnreadCalls.add(chatId)
        _chats.value = _chats.value.map { if (it.id == chatId) it.copy(unreadCount = 0) else it }
        return Result.success(Unit)
    }

    // ── Organisation ─────────────────────────────────────────────────────────

    override suspend fun pinChat(chatId: String, pinned: Boolean): Result<Unit> {
        consumeFailure()?.let { return it }
        pinnedIds.add(chatId to pinned)
        _chats.value = _chats.value.map { if (it.id == chatId) it.copy(isPinned = pinned) else it }
        return Result.success(Unit)
    }

    override suspend fun archiveChat(chatId: String, archived: Boolean): Result<Unit> {
        consumeFailure()?.let { return it }
        archivedIds.add(chatId to archived)
        _chats.value = _chats.value.map { if (it.id == chatId) it.copy(isArchived = archived) else it }
        return Result.success(Unit)
    }

    override suspend fun muteChat(chatId: String, muteUntil: Long): Result<Unit> {
        consumeFailure()?.let { return it }
        mutedIds.add(chatId to muteUntil)
        _chats.value = _chats.value.map { if (it.id == chatId) it.copy(muteUntil = muteUntil) else it }
        return Result.success(Unit)
    }

    override suspend fun deleteChat(chatId: String): Result<Unit> {
        consumeFailure()?.let { return it }
        deletedIds.add(chatId)
        _chats.value = _chats.value.filter { it.id != chatId }
        return Result.success(Unit)
    }

    // ── Group management ──────────────────────────────────────────────────────

    override suspend fun getOrCreateChat(participantId: String): Result<Chat> {
        consumeFailure()?.let { return it }
        val existing = _chats.value.find { it.participants.contains(participantId) }
        if (existing != null) return Result.success(existing)
        val chat = Chat(id = "chat-$participantId", participants = listOf(participantId))
        _chats.value = _chats.value + chat
        return Result.success(chat)
    }

    override suspend fun createGroup(name: String, participantIds: List<String>): Result<Chat> {
        consumeFailure()?.let { return it }
        val chat = Chat(id = "group-$name", name = name, participants = participantIds)
        _chats.value = _chats.value + chat
        return Result.success(chat)
    }

    override suspend fun updateGroupDescription(chatId: String, description: String): Result<Unit> {
        consumeFailure()?.let { return it }
        _chats.value = _chats.value.map { if (it.id == chatId) it.copy(description = description) else it }
        return Result.success(Unit)
    }

    override suspend fun generateInviteLink(chatId: String): Result<String> {
        consumeFailure()?.let { return it }
        val token = "invite-$chatId"
        _chats.value = _chats.value.map { if (it.id == chatId) it.copy(inviteLink = token) else it }
        return Result.success(token)
    }

    override suspend fun revokeInviteLink(chatId: String): Result<Unit> {
        consumeFailure()?.let { return it }
        _chats.value = _chats.value.map { if (it.id == chatId) it.copy(inviteLink = null) else it }
        return Result.success(Unit)
    }

    override suspend fun joinGroupViaLink(inviteToken: String): Result<Chat> {
        consumeFailure()?.let { return it }
        val chat = _chats.value.find { it.inviteLink == inviteToken }
            ?: return Result.failure(NoSuchElementException(inviteToken))
        return Result.success(chat)
    }

    override suspend fun setRequireApproval(chatId: String, enabled: Boolean): Result<Unit> {
        consumeFailure()?.let { return it }
        _chats.value = _chats.value.map { if (it.id == chatId) it.copy(requireApproval = enabled) else it }
        return Result.success(Unit)
    }

    override suspend fun approveMember(chatId: String, userId: String): Result<Unit> {
        consumeFailure()?.let { return it }
        _chats.value = _chats.value.map { chat ->
            if (chat.id == chatId) chat.copy(
                pendingMembers = chat.pendingMembers - userId,
                participants = chat.participants + userId,
            ) else chat
        }
        return Result.success(Unit)
    }

    override suspend fun rejectMember(chatId: String, userId: String): Result<Unit> {
        consumeFailure()?.let { return it }
        _chats.value = _chats.value.map { chat ->
            if (chat.id == chatId) chat.copy(pendingMembers = chat.pendingMembers - userId) else chat
        }
        return Result.success(Unit)
    }

    override suspend fun leaveGroup(chatId: String): Result<Unit> {
        consumeFailure()?.let { return it }
        _chats.value = _chats.value.filter { it.id != chatId }
        return Result.success(Unit)
    }

    override suspend fun addGroupMember(chatId: String, userId: String): Result<Unit> {
        consumeFailure()?.let { return it }
        _chats.value = _chats.value.map { chat ->
            if (chat.id == chatId) chat.copy(participants = chat.participants + userId) else chat
        }
        return Result.success(Unit)
    }

    override suspend fun removeGroupMember(chatId: String, userId: String): Result<Unit> {
        consumeFailure()?.let { return it }
        _chats.value = _chats.value.map { chat ->
            if (chat.id == chatId) chat.copy(participants = chat.participants - userId) else chat
        }
        return Result.success(Unit)
    }

    override suspend fun uploadGroupAvatar(chatId: String, uri: String): Result<String> {
        consumeFailure()?.let { return it }
        val url = "https://fake/$chatId/avatar"
        _chats.value = _chats.value.map { if (it.id == chatId) it.copy(avatarUrl = url) else it }
        return Result.success(url)
    }

    override suspend fun updateGroup(chatId: String, name: String?, avatarUrl: String?): Result<Unit> {
        consumeFailure()?.let { return it }
        _chats.value = _chats.value.map { chat ->
            if (chat.id == chatId) chat.copy(
                name = name ?: chat.name,
                avatarUrl = avatarUrl ?: chat.avatarUrl,
            ) else chat
        }
        return Result.success(Unit)
    }

    // ── Group permissions ─────────────────────────────────────────────────────

    override suspend fun updateGroupPermissions(chatId: String, permissions: GroupPermissions): Result<Unit> {
        consumeFailure()?.let { return it }
        _chats.value = _chats.value.map { if (it.id == chatId) it.copy(permissions = permissions) else it }
        return Result.success(Unit)
    }

    override suspend fun promoteToAdmin(chatId: String, userId: String): Result<Unit> {
        consumeFailure()?.let { return it }
        _chats.value = _chats.value.map { chat ->
            if (chat.id == chatId) chat.copy(admins = chat.admins + userId) else chat
        }
        return Result.success(Unit)
    }

    override suspend fun demoteFromAdmin(chatId: String, userId: String): Result<Unit> {
        consumeFailure()?.let { return it }
        _chats.value = _chats.value.map { chat ->
            if (chat.id == chatId) chat.copy(admins = chat.admins - userId) else chat
        }
        return Result.success(Unit)
    }

    override suspend fun transferOwnership(chatId: String, newOwnerId: String): Result<Unit> {
        consumeFailure()?.let { return it }
        _chats.value = _chats.value.map { if (it.id == chatId) it.copy(owner = newOwnerId) else it }
        return Result.success(Unit)
    }

    // ── Broadcast ─────────────────────────────────────────────────────────────

    override suspend fun createBroadcastList(name: String, recipientIds: List<String>): Result<Chat> {
        consumeFailure()?.let { return it }
        val chat = Chat(id = "broadcast-$name", name = name, participants = recipientIds)
        _chats.value = _chats.value + chat
        return Result.success(chat)
    }
}
