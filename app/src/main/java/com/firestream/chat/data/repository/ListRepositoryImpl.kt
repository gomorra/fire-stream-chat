package com.firestream.chat.data.repository

import com.firestream.chat.data.local.dao.ListDao
import com.firestream.chat.data.local.dao.MessageDao
import com.firestream.chat.data.local.entity.ListEntity
import com.firestream.chat.data.remote.firebase.FirebaseAuthSource
import com.firestream.chat.data.remote.firebase.FirestoreListSource
import com.firestream.chat.domain.model.ListData
import com.firestream.chat.domain.model.ListItem
import com.firestream.chat.domain.model.ListType
import com.firestream.chat.domain.model.Message
import com.firestream.chat.domain.model.MessageType
import com.firestream.chat.domain.repository.ChatRepository
import com.firestream.chat.domain.repository.ListRepository
import com.firestream.chat.domain.repository.MessageRepository
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
    private val authSource: FirebaseAuthSource,
    private val chatRepository: dagger.Lazy<ChatRepository>,
    private val messageRepository: dagger.Lazy<MessageRepository>
) : ListRepository {

    override fun observeList(listId: String): Flow<ListData?> = channelFlow {
        launch {
            try {
                listSource.observeList(listId).collectLatest { listData ->
                    if (listData != null) {
                        listDao.insert(ListEntity.fromDomain(listData))
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
                }
            } catch (_: Exception) { }
        }
        listDao.getAll()
            .map { entities -> entities.map { it.toDomain() } }
            .collect { send(it) }
    }

    override suspend fun createList(title: String, type: ListType, chatId: String?): Result<ListData> {
        return try {
            val userId = authSource.currentUserId ?: throw Exception("Not authenticated")
            val now = System.currentTimeMillis()
            val listData = ListData(
                title = title,
                type = type,
                createdBy = userId,
                createdAt = now,
                updatedAt = now,
                participants = listOf(userId)
            )

            val remoteId = listSource.createList(listData)
            val created = listData.copy(id = remoteId)
            listDao.insert(ListEntity.fromDomain(created))

            // If created from a chat, auto-send a list message
            if (chatId != null) {
                messageRepository.get().sendListMessage(chatId, remoteId, title)
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
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    override suspend fun toggleItemChecked(listId: String, itemId: String): Result<Unit> {
        return try {
            val entity = listDao.getById(listId) ?: throw Exception("List not found")
            val list = entity.toDomain()
            val updatedItems = list.items.map { item ->
                if (item.id == itemId) item.copy(isChecked = !item.isChecked) else item
            }
            listSource.updateListItems(listId, updatedItems)
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
}
