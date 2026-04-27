package com.firestream.chat.data.remote.pocketbase

import com.firestream.chat.data.remote.source.ChatSource
import com.firestream.chat.domain.model.Chat
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import javax.inject.Inject
import javax.inject.Singleton

/** Step 4 stub. Real impl in step 6. */
@Singleton
class PocketBaseChatSource @Inject constructor() : ChatSource {
    override fun observeChatsForUser(uid: String): Flow<List<Chat>> = emptyFlow()
    override fun observeTypingUsers(chatId: String): Flow<List<String>> = emptyFlow()

    override suspend fun getChat(chatId: String, currentUid: String): Chat? =
        throw NotImplementedError("PB v0 stub")

    override suspend fun findIndividualChat(participants: List<String>, currentUid: String): Chat? =
        throw NotImplementedError("PB v0 stub")

    override suspend fun getChatIdForInviteToken(token: String): String? =
        throw NotImplementedError("PB v0 stub")

    override suspend fun createChat(data: Map<String, Any?>): String =
        throw NotImplementedError("PB v0 stub")

    override suspend fun updateChatFields(chatId: String, updates: Map<String, Any?>): Unit =
        throw NotImplementedError("PB v0 stub")

    override suspend fun deleteChatDocument(chatId: String): Unit =
        throw NotImplementedError("PB v0 stub")

    override suspend fun addToArrayField(chatId: String, field: String, value: Any): Unit =
        throw NotImplementedError("PB v0 stub")

    override suspend fun removeFromArrayField(chatId: String, field: String, value: Any): Unit =
        throw NotImplementedError("PB v0 stub")

    override suspend fun setTyping(chatId: String, uid: String, isTyping: Boolean): Unit =
        throw NotImplementedError("PB v0 stub")

    override suspend fun resetUnreadCount(chatId: String, uid: String): Unit =
        throw NotImplementedError("PB v0 stub")

    override suspend fun setInviteLink(chatId: String, token: String, createdBy: String): Unit =
        throw NotImplementedError("PB v0 stub")

    override suspend fun clearInviteLink(chatId: String, existingToken: String?): Unit =
        throw NotImplementedError("PB v0 stub")

    override suspend fun getExistingInviteToken(chatId: String): String? =
        throw NotImplementedError("PB v0 stub")

    override suspend fun approveMember(chatId: String, userId: String): Unit =
        throw NotImplementedError("PB v0 stub")

    override suspend fun leaveGroupPromotingAdmin(
        chatId: String,
        leavingUid: String,
        newAdminUid: String
    ): Unit = throw NotImplementedError("PB v0 stub")

    override suspend fun leaveGroupRemovingSelf(chatId: String, leavingUid: String): Unit =
        throw NotImplementedError("PB v0 stub")

    override suspend fun transferOwnership(chatId: String, newOwnerId: String): Unit =
        throw NotImplementedError("PB v0 stub")
}
