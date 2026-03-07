package com.firestream.chat.domain.repository

import com.firestream.chat.domain.model.Chat
import kotlinx.coroutines.flow.Flow

interface ChatRepository {
    fun getChats(): Flow<List<Chat>>
    suspend fun getChatById(chatId: String): Result<Chat>
    suspend fun getOrCreateChat(participantId: String): Result<Chat>
    suspend fun createGroup(name: String, participantIds: List<String>): Result<Chat>
    suspend fun updateGroup(chatId: String, name: String?, avatarUrl: String?): Result<Unit>
    suspend fun addGroupMember(chatId: String, userId: String): Result<Unit>
    suspend fun removeGroupMember(chatId: String, userId: String): Result<Unit>
}
