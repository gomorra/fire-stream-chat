package com.firestream.chat.data.remote.pocketbase

import com.firestream.chat.data.remote.source.ListSource
import com.firestream.chat.domain.model.GenericListStyle
import com.firestream.chat.domain.model.ListData
import com.firestream.chat.domain.model.ListItem
import com.firestream.chat.domain.model.ListType
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import javax.inject.Inject
import javax.inject.Singleton

/** Step 4 stub. Stays a stub through v0; lists are out of scope. */
@Singleton
class PocketBaseListSource @Inject constructor() : ListSource {
    override fun observeList(listId: String): Flow<ListData?> = emptyFlow()
    override fun observeItems(listId: String): Flow<List<ListItem>> = emptyFlow()
    override fun observeSharedListsForChat(chatId: String): Flow<List<ListData>> = emptyFlow()
    override fun observeMyLists(userId: String): Flow<List<ListData>> = emptyFlow()

    override suspend fun getList(listId: String): ListData? =
        throw NotImplementedError("PB v0 stub")

    override suspend fun createList(list: ListData): String =
        throw NotImplementedError("PB v0 stub")

    override suspend fun addItem(listId: String, item: ListItem): Unit =
        throw NotImplementedError("PB v0 stub")

    override suspend fun setItemChecked(listId: String, itemId: String, checked: Boolean): Unit =
        throw NotImplementedError("PB v0 stub")

    override suspend fun updateItem(
        listId: String,
        itemId: String,
        text: String,
        quantity: String?,
        unit: String?
    ): Unit = throw NotImplementedError("PB v0 stub")

    override suspend fun deleteItem(listId: String, itemId: String, wasChecked: Boolean): Unit =
        throw NotImplementedError("PB v0 stub")

    override suspend fun clearCheckedItems(listId: String, checkedItemIds: List<String>): Unit =
        throw NotImplementedError("PB v0 stub")

    override suspend fun reorderItems(listId: String, orderedIds: List<String>): Unit =
        throw NotImplementedError("PB v0 stub")

    override suspend fun migrateEmbeddedItemsIfNeeded(listId: String) {
        // No-op stub — migration is Firestore-only legacy.
    }

    override suspend fun deleteList(listId: String): Unit =
        throw NotImplementedError("PB v0 stub")

    override suspend fun addParticipant(listId: String, userId: String): Unit =
        throw NotImplementedError("PB v0 stub")

    override suspend fun removeParticipant(listId: String, userId: String): Unit =
        throw NotImplementedError("PB v0 stub")

    override suspend fun removeParticipants(listId: String, userIds: List<String>): Unit =
        throw NotImplementedError("PB v0 stub")

    override suspend fun updateListTitle(listId: String, title: String): Unit =
        throw NotImplementedError("PB v0 stub")

    override suspend fun shareList(
        listId: String,
        participantIds: List<String>,
        chatId: String
    ): Unit = throw NotImplementedError("PB v0 stub")

    override suspend fun updateSharedChatIds(listId: String, chatId: String): Unit =
        throw NotImplementedError("PB v0 stub")

    override suspend fun unshareList(
        listId: String,
        participantIdsToRemove: List<String>,
        chatId: String
    ): Unit = throw NotImplementedError("PB v0 stub")

    override suspend fun updateListType(listId: String, type: ListType): Unit =
        throw NotImplementedError("PB v0 stub")

    override suspend fun updateGenericStyle(listId: String, style: GenericListStyle): Unit =
        throw NotImplementedError("PB v0 stub")
}
