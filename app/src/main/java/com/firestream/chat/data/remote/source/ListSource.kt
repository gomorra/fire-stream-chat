package com.firestream.chat.data.remote.source

import com.firestream.chat.domain.model.GenericListStyle
import com.firestream.chat.domain.model.ListData
import com.firestream.chat.domain.model.ListItem
import com.firestream.chat.domain.model.ListType
import kotlinx.coroutines.flow.Flow

/** Backend-neutral shared-list boundary. Stub on pocketbase flavor in v0. */
interface ListSource {
    fun observeList(listId: String): Flow<ListData?>
    fun observeItems(listId: String): Flow<List<ListItem>>
    fun observeSharedListsForChat(chatId: String): Flow<List<ListData>>
    fun observeMyLists(userId: String): Flow<List<ListData>>

    suspend fun getList(listId: String): ListData?
    suspend fun createList(list: ListData): String

    suspend fun addItem(listId: String, item: ListItem)
    suspend fun setItemChecked(listId: String, itemId: String, checked: Boolean)
    suspend fun updateItem(listId: String, itemId: String, text: String, quantity: String?, unit: String?)
    suspend fun deleteItem(listId: String, itemId: String, wasChecked: Boolean)
    suspend fun clearCheckedItems(listId: String, checkedItemIds: List<String>)
    suspend fun reorderItems(listId: String, orderedIds: List<String>)

    /** One-shot legacy migration (pre-refactor embedded items array → subcollection). */
    suspend fun migrateEmbeddedItemsIfNeeded(listId: String)

    suspend fun deleteList(listId: String)
    suspend fun addParticipant(listId: String, userId: String)
    suspend fun removeParticipant(listId: String, userId: String)
    suspend fun removeParticipants(listId: String, userIds: List<String>)
    suspend fun updateListTitle(listId: String, title: String)
    suspend fun shareList(listId: String, participantIds: List<String>, chatId: String)
    suspend fun updateSharedChatIds(listId: String, chatId: String)
    suspend fun unshareList(listId: String, participantIdsToRemove: List<String>, chatId: String)
    suspend fun updateListType(listId: String, type: ListType)
    suspend fun updateGenericStyle(listId: String, style: GenericListStyle)
}
