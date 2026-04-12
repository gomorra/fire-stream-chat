package com.firestream.chat.test.fakes

import com.firestream.chat.domain.model.Chat
import com.firestream.chat.domain.model.GroupPermissions
import com.firestream.chat.domain.repository.ChatRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.emptyFlow

/**
 * In-memory fake [ChatRepository] that records calls relevant to tests.
 * Methods not used by tests throw to fail fast.
 */
class FakeChatRepository : ChatRepository {

    /** IDs passed to [resetUnreadCount], in call order. */
    val resetUnreadCalls: MutableList<String> = mutableListOf()

    /** When non-null, [resetUnreadCount] fails with this throwable. */
    var resetUnreadError: Throwable? = null

    /** Stub chats emitted by [getChats]; tests can mutate/replace this. */
    var chats: List<Chat> = emptyList()

    /** Stub chat returned by [getChatById]; tests can set this. */
    var chatByIdResult: Result<Chat>? = null

    private val chatsFlow = MutableSharedFlow<List<Chat>>(replay = 1)

    fun emitChats(list: List<Chat>) {
        chats = list
        chatsFlow.tryEmit(list)
    }

    override suspend fun resetUnreadCount(chatId: String): Result<Unit> {
        resetUnreadCalls.add(chatId)
        resetUnreadError?.let { return Result.failure(it) }
        return Result.success(Unit)
    }

    override fun getChats(): Flow<List<Chat>> = chatsFlow

    override suspend fun getChatById(chatId: String): Result<Chat> =
        chatByIdResult ?: Result.failure(IllegalStateException("No stub chat set"))

    override fun observeTyping(chatId: String): Flow<List<String>> = emptyFlow()

    override suspend fun setTyping(chatId: String, isTyping: Boolean) = Unit

    // --- Unused by current tests; throw to surface accidental usage. ---

    override suspend fun getOrCreateChat(participantId: String): Result<Chat> =
        error("FakeChatRepository.getOrCreateChat not implemented")

    override suspend fun createGroup(name: String, participantIds: List<String>): Result<Chat> =
        error("FakeChatRepository.createGroup not implemented")

    override suspend fun uploadGroupAvatar(chatId: String, uri: String): Result<String> =
        error("FakeChatRepository.uploadGroupAvatar not implemented")

    override suspend fun updateGroup(
        chatId: String,
        name: String?,
        avatarUrl: String?,
    ): Result<Unit> = error("FakeChatRepository.updateGroup not implemented")

    override suspend fun addGroupMember(chatId: String, userId: String): Result<Unit> =
        error("FakeChatRepository.addGroupMember not implemented")

    override suspend fun removeGroupMember(chatId: String, userId: String): Result<Unit> =
        error("FakeChatRepository.removeGroupMember not implemented")

    override suspend fun deleteChat(chatId: String): Result<Unit> =
        error("FakeChatRepository.deleteChat not implemented")

    override suspend fun pinChat(chatId: String, pinned: Boolean): Result<Unit> =
        error("FakeChatRepository.pinChat not implemented")

    override suspend fun archiveChat(chatId: String, archived: Boolean): Result<Unit> =
        error("FakeChatRepository.archiveChat not implemented")

    override suspend fun muteChat(chatId: String, muteUntil: Long): Result<Unit> =
        error("FakeChatRepository.muteChat not implemented")

    override suspend fun updateGroupDescription(
        chatId: String,
        description: String,
    ): Result<Unit> = error("FakeChatRepository.updateGroupDescription not implemented")

    override suspend fun generateInviteLink(chatId: String): Result<String> =
        error("FakeChatRepository.generateInviteLink not implemented")

    override suspend fun revokeInviteLink(chatId: String): Result<Unit> =
        error("FakeChatRepository.revokeInviteLink not implemented")

    override suspend fun joinGroupViaLink(inviteToken: String): Result<Chat> =
        error("FakeChatRepository.joinGroupViaLink not implemented")

    override suspend fun setRequireApproval(chatId: String, enabled: Boolean): Result<Unit> =
        error("FakeChatRepository.setRequireApproval not implemented")

    override suspend fun approveMember(chatId: String, userId: String): Result<Unit> =
        error("FakeChatRepository.approveMember not implemented")

    override suspend fun rejectMember(chatId: String, userId: String): Result<Unit> =
        error("FakeChatRepository.rejectMember not implemented")

    override suspend fun leaveGroup(chatId: String): Result<Unit> =
        error("FakeChatRepository.leaveGroup not implemented")

    override suspend fun updateGroupPermissions(
        chatId: String,
        permissions: GroupPermissions,
    ): Result<Unit> = error("FakeChatRepository.updateGroupPermissions not implemented")

    override suspend fun promoteToAdmin(chatId: String, userId: String): Result<Unit> =
        error("FakeChatRepository.promoteToAdmin not implemented")

    override suspend fun demoteFromAdmin(chatId: String, userId: String): Result<Unit> =
        error("FakeChatRepository.demoteFromAdmin not implemented")

    override suspend fun transferOwnership(chatId: String, newOwnerId: String): Result<Unit> =
        error("FakeChatRepository.transferOwnership not implemented")

    override suspend fun createBroadcastList(
        name: String,
        recipientIds: List<String>,
    ): Result<Chat> = error("FakeChatRepository.createBroadcastList not implemented")
}
