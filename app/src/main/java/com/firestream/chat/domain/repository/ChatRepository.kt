package com.firestream.chat.domain.repository

import com.firestream.chat.domain.model.Chat
import com.firestream.chat.domain.model.GroupPermissions
import kotlinx.coroutines.flow.Flow

interface ChatRepository {
    fun getChats(): Flow<List<Chat>>
    suspend fun getChatById(chatId: String): Result<Chat>
    suspend fun getOrCreateChat(participantId: String): Result<Chat>
    suspend fun createGroup(name: String, participantIds: List<String>): Result<Chat>
    suspend fun updateGroup(chatId: String, name: String?, avatarUrl: String?): Result<Unit>
    suspend fun addGroupMember(chatId: String, userId: String): Result<Unit>
    suspend fun removeGroupMember(chatId: String, userId: String): Result<Unit>
    suspend fun deleteChat(chatId: String): Result<Unit>
    fun observeTyping(chatId: String): Flow<List<String>>
    suspend fun setTyping(chatId: String, isTyping: Boolean)
    // Phase 2: chat organisation
    suspend fun pinChat(chatId: String, pinned: Boolean): Result<Unit>
    suspend fun archiveChat(chatId: String, archived: Boolean): Result<Unit>
    suspend fun muteChat(chatId: String, muteUntil: Long): Result<Unit>
    // Phase 5: group management
    suspend fun updateGroupDescription(chatId: String, description: String): Result<Unit>
    suspend fun generateInviteLink(chatId: String): Result<String>
    suspend fun revokeInviteLink(chatId: String): Result<Unit>
    suspend fun joinGroupViaLink(inviteToken: String): Result<Chat>
    suspend fun setRequireApproval(chatId: String, enabled: Boolean): Result<Unit>
    suspend fun approveMember(chatId: String, userId: String): Result<Unit>
    suspend fun rejectMember(chatId: String, userId: String): Result<Unit>
    suspend fun leaveGroup(chatId: String): Result<Unit>
    // Phase 5.2: group permissions
    suspend fun updateGroupPermissions(chatId: String, permissions: GroupPermissions): Result<Unit>
    suspend fun promoteToAdmin(chatId: String, userId: String): Result<Unit>
    suspend fun demoteFromAdmin(chatId: String, userId: String): Result<Unit>
    suspend fun transferOwnership(chatId: String, newOwnerId: String): Result<Unit>
    // Phase 5.5: broadcast lists
    suspend fun createBroadcastList(name: String, recipientIds: List<String>): Result<Chat>
}
