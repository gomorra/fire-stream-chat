package com.firestream.chat.domain.repository

import com.firestream.chat.domain.model.ListData
import com.firestream.chat.domain.model.ListType
import com.firestream.chat.domain.model.Message
import kotlinx.coroutines.flow.Flow

interface ListRepository {
    fun observeList(listId: String): Flow<ListData?>
    fun observeMyLists(): Flow<List<ListData>>
    suspend fun createList(title: String, type: ListType, chatId: String? = null): Result<ListData>
    suspend fun addItem(listId: String, text: String, quantity: String? = null, unit: String? = null): Result<Unit>
    suspend fun removeItem(listId: String, itemId: String): Result<Unit>
    suspend fun toggleItemChecked(listId: String, itemId: String): Result<Unit>
    suspend fun updateItem(listId: String, itemId: String, text: String, quantity: String? = null, unit: String? = null): Result<Unit>
    suspend fun reorderItems(listId: String, items: List<com.firestream.chat.domain.model.ListItem>): Result<Unit>
    suspend fun updateListTitle(listId: String, title: String): Result<Unit>
    suspend fun updateListType(listId: String, type: ListType): Result<Unit>
    suspend fun deleteList(listId: String): Result<Unit>
    suspend fun shareListToChat(listId: String, chatId: String): Result<Message>
    fun getSharedListsForChat(chatId: String): Flow<List<ListData>>
}
