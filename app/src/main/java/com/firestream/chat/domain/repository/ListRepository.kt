package com.firestream.chat.domain.repository

import com.firestream.chat.domain.model.GenericListStyle
import com.firestream.chat.domain.model.ListData
import com.firestream.chat.domain.model.ListHistoryEntry
import com.firestream.chat.domain.model.ListType
import com.firestream.chat.domain.model.Message
import kotlinx.coroutines.flow.Flow

interface ListRepository {
    fun observeList(listId: String): Flow<ListData?>
    fun observeMyLists(): Flow<List<ListData>>
    suspend fun createList(title: String, type: ListType, chatId: String? = null, genericStyle: GenericListStyle = GenericListStyle.BULLET): Result<ListData>
    suspend fun addItem(listId: String, text: String, quantity: String? = null, unit: String? = null): Result<Unit>
    suspend fun removeItem(listId: String, itemId: String): Result<Unit>
    suspend fun clearCheckedItems(listId: String): Result<List<String>>
    suspend fun toggleItemChecked(listId: String, itemId: String): Result<Unit>
    suspend fun updateItem(listId: String, itemId: String, text: String, quantity: String? = null, unit: String? = null): Result<Unit>
    suspend fun reorderItems(listId: String, items: List<com.firestream.chat.domain.model.ListItem>): Result<Unit>
    suspend fun updateListTitle(listId: String, title: String): Result<Unit>
    suspend fun updateListType(listId: String, type: ListType): Result<Unit>
    suspend fun updateGenericStyle(listId: String, style: GenericListStyle): Result<Unit>
    suspend fun deleteList(listId: String): Result<Unit>
    suspend fun shareListToChat(listId: String, chatId: String): Result<Message>
    fun getSharedListsForChat(chatId: String): Flow<List<ListData>>
    // History
    fun observeHistory(listId: String): Flow<List<ListHistoryEntry>>
    suspend fun addHistoryEntry(listId: String, entry: ListHistoryEntry): Result<Unit>
    // Shared chat tracking
    suspend fun updateSharedChatIds(listId: String, chatId: String): Result<Unit>
    suspend fun unshareListFromChat(listId: String, chatId: String): Result<Unit>
    suspend fun removeParticipant(listId: String, userId: String): Result<Unit>
    suspend fun removeParticipants(listId: String, userIds: List<String>): Result<Unit>
    /** One-shot fetch from Firestore → Room cache. Used when a shared/unshared message arrives. */
    suspend fun fetchAndCacheList(listId: String)
}
