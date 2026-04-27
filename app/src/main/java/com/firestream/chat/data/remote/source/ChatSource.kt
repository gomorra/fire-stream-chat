package com.firestream.chat.data.remote.source

import com.firestream.chat.domain.model.Chat
import kotlinx.coroutines.flow.Flow

/**
 * Backend-neutral chat metadata boundary — chat documents, group permissions,
 * invite links, typing indicators. Messages are owned by [MessageSource].
 */
interface ChatSource {
    fun observeChatsForUser(uid: String): Flow<List<Chat>>
    fun observeTypingUsers(chatId: String): Flow<List<String>>

    suspend fun getChat(chatId: String, currentUid: String): Chat?
    suspend fun findIndividualChat(participants: List<String>, currentUid: String): Chat?
    suspend fun getChatIdForInviteToken(token: String): String?

    suspend fun createChat(data: Map<String, Any?>): String
    suspend fun updateChatFields(chatId: String, updates: Map<String, Any?>)
    suspend fun deleteChatDocument(chatId: String)
    suspend fun addToArrayField(chatId: String, field: String, value: Any)
    suspend fun removeFromArrayField(chatId: String, field: String, value: Any)
    suspend fun setTyping(chatId: String, uid: String, isTyping: Boolean)
    suspend fun resetUnreadCount(chatId: String, uid: String)

    suspend fun setInviteLink(chatId: String, token: String, createdBy: String)
    suspend fun clearInviteLink(chatId: String, existingToken: String?)
    suspend fun getExistingInviteToken(chatId: String): String?

    suspend fun approveMember(chatId: String, userId: String)
    suspend fun leaveGroupPromotingAdmin(chatId: String, leavingUid: String, newAdminUid: String)
    suspend fun leaveGroupRemovingSelf(chatId: String, leavingUid: String)
    suspend fun transferOwnership(chatId: String, newOwnerId: String)
}
