package com.firestream.chat.data.repository

import android.util.Log
import com.firestream.chat.data.local.dao.ListDao
import com.firestream.chat.data.local.dao.MessageDao
import com.firestream.chat.data.local.entity.ListEntity
import com.firestream.chat.data.remote.firebase.FirebaseAuthSource
import com.firestream.chat.data.remote.firebase.FirestoreListHistorySource
import com.firestream.chat.data.remote.firebase.FirestoreListSource
import com.firestream.chat.domain.model.GenericListStyle
import com.firestream.chat.domain.model.HistoryAction
import com.firestream.chat.domain.model.ListData
import com.firestream.chat.domain.model.ListHistoryEntry
import com.firestream.chat.domain.model.ListItem
import com.firestream.chat.domain.model.ListType
import com.firestream.chat.domain.model.Message
import com.firestream.chat.domain.model.MessageType
import com.firestream.chat.domain.repository.ChatRepository
import com.firestream.chat.domain.repository.ListRepository
import com.firestream.chat.domain.repository.MessageRepository
import com.firestream.chat.domain.repository.UserRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ListRepositoryImpl @Inject constructor(
    private val listDao: ListDao,
    private val messageDao: MessageDao,
    private val listSource: FirestoreListSource,
    private val historySource: FirestoreListHistorySource,
    private val authSource: FirebaseAuthSource,
    private val chatRepository: dagger.Lazy<ChatRepository>,
    private val messageRepository: dagger.Lazy<MessageRepository>,
    private val userRepository: dagger.Lazy<UserRepository>
) : ListRepository {

    private val historyScope = CoroutineScope(SupervisorJob())

    private fun recordHistory(listId: String, action: HistoryAction, itemId: String? = null, itemText: String? = null) {
        val userId = authSource.currentUserId ?: return
        historyScope.launch {
            try {
                val user = userRepository.get().getUserById(userId).getOrNull()
                val entry = ListHistoryEntry(
                    action = action,
                    itemId = itemId,
                    itemText = itemText,
                    userId = userId,
                    userName = user?.displayName ?: "Unknown"
                )
                historySource.addEntry(listId, entry)
            } catch (e: Exception) {
                Log.w("ListRepo", "Failed to record history", e)
            }
        }
    }

    override fun observeList(listId: String): Flow<ListData?> = channelFlow {
        launch {
            try {
                listSource.observeList(listId).collectLatest { listData ->
                    if (listData != null) {
                        // Preserve local sharedChatIds if Firestore doc doesn't yet reflect them
                        // (Firestore propagation delay can cause the snapshot to arrive before
                        // the arrayUnion update, wiping local sharedChatIds and breaking the debounce flush)
                        val mergedSharedChatIds = if (listData.sharedChatIds.isEmpty()) {
                            listDao.getById(listId)?.toDomain()?.sharedChatIds ?: emptyList()
                        } else {
                            listData.sharedChatIds
                        }
                        listDao.insert(ListEntity.fromDomain(listData.copy(sharedChatIds = mergedSharedChatIds)))
                    } else {
                        listDao.delete(listId)
                    }
                }
            } catch (_: Exception) { }
        }
        listDao.observeById(listId)
            .map { it?.toDomain() }
            .collect { send(it) }
    }

    override fun observeMyLists(): Flow<List<ListData>> = channelFlow {
        val userId = authSource.currentUserId ?: return@channelFlow
        launch {
            try {
                listSource.observeMyLists(userId).collectLatest { lists ->
                    listDao.insertAll(lists.map { ListEntity.fromDomain(it) })
                    val activeIds = lists.map { it.id }
                    if (activeIds.isNotEmpty()) listDao.deleteExcept(activeIds) else listDao.deleteExcept(emptyList())
                }
            } catch (_: Exception) { }
        }
        listDao.getAll()
            .map { entities -> entities.map { it.toDomain() } }
            .collect { send(it) }
    }

    override suspend fun createList(title: String, type: ListType, chatId: String?, genericStyle: GenericListStyle): Result<ListData> {
        return try {
            val userId = authSource.currentUserId ?: throw Exception("Not authenticated")
            val now = System.currentTimeMillis()
            val listData = ListData(
                title = title,
                type = type,
                genericStyle = genericStyle,
                createdBy = userId,
                createdAt = now,
                updatedAt = now,
                participants = listOf(userId)
            )

            val remoteId = listSource.createList(listData)
            val created = listData.copy(id = remoteId)
            listDao.insert(ListEntity.fromDomain(created))

            recordHistory(remoteId, HistoryAction.CREATED)

            // If created from a chat, auto-send a list message and track sharedChatId
            if (chatId != null) {
                messageRepository.get().sendListMessage(chatId, remoteId, title)
                listSource.updateSharedChatIds(remoteId, chatId)
            }

            Result.success(created)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    override suspend fun addItem(listId: String, text: String, quantity: String?, unit: String?): Result<Unit> {
        return try {
            val userId = authSource.currentUserId ?: throw Exception("Not authenticated")
            val item = ListItem(
                id = UUID.randomUUID().toString(),
                text = text,
                quantity = quantity,
                unit = unit,
                addedBy = userId
            )
            listSource.addItem(listId, item)
            recordHistory(listId, HistoryAction.ITEM_ADDED, itemId = item.id, itemText = text)
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    override suspend fun removeItem(listId: String, itemId: String): Result<Unit> {
        return try {
            val entity = listDao.getById(listId) ?: throw Exception("List not found")
            val list = entity.toDomain()
            val item = list.items.find { it.id == itemId } ?: throw Exception("Item not found")
            listSource.removeItem(listId, item)
            recordHistory(listId, HistoryAction.ITEM_REMOVED, itemId = itemId, itemText = item.text)
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    override suspend fun clearCheckedItems(listId: String): Result<List<String>> {
        return try {
            val entity = listDao.getById(listId) ?: throw Exception("List not found")
            val list = entity.toDomain()
            val checkedItems = list.items.filter { it.isChecked }
            if (checkedItems.isEmpty()) return Result.success(emptyList())
            val remaining = list.items.filter { !it.isChecked }
            listSource.updateListItems(listId, remaining)
            checkedItems.forEach { item ->
                recordHistory(listId, HistoryAction.ITEM_REMOVED, itemId = item.id, itemText = item.text)
            }
            Result.success(checkedItems.map { it.text })
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    override suspend fun toggleItemChecked(listId: String, itemId: String): Result<Unit> {
        return try {
            val entity = listDao.getById(listId) ?: throw Exception("List not found")
            val list = entity.toDomain()
            val targetItem = list.items.find { it.id == itemId }
            val updatedItems = list.items.map { item ->
                if (item.id == itemId) item.copy(isChecked = !item.isChecked) else item
            }
            listSource.updateListItems(listId, updatedItems)
            if (targetItem != null) {
                val action = if (targetItem.isChecked) HistoryAction.ITEM_UNCHECKED else HistoryAction.ITEM_CHECKED
                recordHistory(listId, action, itemId = itemId, itemText = targetItem.text)
            }
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    override suspend fun updateItem(
        listId: String,
        itemId: String,
        text: String,
        quantity: String?,
        unit: String?
    ): Result<Unit> {
        return try {
            val entity = listDao.getById(listId) ?: throw Exception("List not found")
            val list = entity.toDomain()
            val updatedItems = list.items.map { item ->
                if (item.id == itemId) item.copy(text = text, quantity = quantity, unit = unit) else item
            }
            listSource.updateListItems(listId, updatedItems)
            recordHistory(listId, HistoryAction.ITEM_MODIFIED, itemId = itemId, itemText = text)
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    override suspend fun reorderItems(listId: String, items: List<ListItem>): Result<Unit> {
        return try {
            listSource.updateListItems(listId, items)
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    override suspend fun updateListTitle(listId: String, title: String): Result<Unit> {
        return try {
            listSource.updateListTitle(listId, title)
            recordHistory(listId, HistoryAction.TITLE_CHANGED, itemText = title)
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    override suspend fun updateListType(listId: String, type: ListType): Result<Unit> {
        return try {
            listSource.updateListType(listId, type)
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    override suspend fun updateGenericStyle(listId: String, style: GenericListStyle): Result<Unit> {
        return try {
            listSource.updateGenericStyle(listId, style)
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    override suspend fun deleteList(listId: String): Result<Unit> {
        return try {
            listSource.deleteList(listId)
            listDao.delete(listId)
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    override suspend fun shareListToChat(listId: String, chatId: String): Result<Message> {
        return try {
            val entity = listDao.getById(listId) ?: throw Exception("List not found")
            val list = entity.toDomain()

            // Add chat participants to list so they can access it
            val chat = chatRepository.get().getChatById(chatId).getOrThrow()
            chat.participants.forEach { participantId ->
                listSource.addParticipant(listId, participantId)
            }

            // Track this chat as a shared destination
            updateSharedChatIds(listId, chatId)

            messageRepository.get().sendListMessage(chatId, listId, list.title)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    override fun getSharedListsForChat(chatId: String): Flow<List<ListData>> = channelFlow {
        messageDao.getMessagesByChatId(chatId)
            .map { messages ->
                messages
                    .filter { it.type == MessageType.LIST.name && it.listId != null }
                    .mapNotNull { it.listId }
                    .distinct()
            }
            .collectLatest { listIds ->
                val lists = listIds.mapNotNull { listDao.getById(it)?.toDomain() }
                send(lists)
            }
    }

    override fun observeHistory(listId: String): Flow<List<ListHistoryEntry>> =
        historySource.observeHistory(listId)

    override suspend fun addHistoryEntry(listId: String, entry: ListHistoryEntry): Result<Unit> {
        return try {
            historySource.addEntry(listId, entry)
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    override suspend fun updateSharedChatIds(listId: String, chatId: String): Result<Unit> {
        return try {
            listSource.updateSharedChatIds(listId, chatId)
            val now = System.currentTimeMillis()
            val entity = listDao.getById(listId)
            if (entity != null) {
                val list = entity.toDomain()
                val updatedIds = (list.sharedChatIds + chatId).distinct()
                listDao.updateSharedChatIds(
                    listId,
                    org.json.JSONArray(updatedIds).toString(),
                    now
                )
            }
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    override suspend fun unshareListFromChat(listId: String, chatId: String): Result<Unit> {
        return try {
            listSource.removeSharedChatId(listId, chatId)
            val entity = listDao.getById(listId)
            if (entity != null) {
                val arr = org.json.JSONArray(entity.sharedChatIds)
                val updatedIds = List(arr.length()) { i -> arr.getString(i) }.filter { it != chatId }
                listDao.updateSharedChatIds(listId, org.json.JSONArray(updatedIds).toString(), System.currentTimeMillis())
            }
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    override suspend fun removeParticipant(listId: String, userId: String): Result<Unit> {
        return try {
            listSource.removeParticipant(listId, userId)
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    override suspend fun removeParticipants(listId: String, userIds: List<String>): Result<Unit> {
        return try {
            listSource.removeParticipants(listId, userIds)
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
}
