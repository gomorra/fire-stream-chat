// region: AGENT-NOTE
// Responsibility: Shared-list CRUD + share/unshare flows. Per-list mutex and
//   resultOf() wrapping for the multi-step share/unshare pipelines that
//   need atomic-feeling semantics across Firestore writes + history entries.
// Owns: ListEntity rows + lists/{id}/items/{itemId} subcollection writes via
//   FirestoreListSource. Denormalized itemCount/checkedCount maintained on the
//   parent metadata doc.
// Collaborators: ListDao, MessageDao (LIST message creation), FirestoreListSource,
//   FirestoreListHistorySource (audit trail, fire-and-forget on SupervisorJob).
// Don't put here: 30s debounce for LIST update fan-out (ListDetailViewModel),
//   chat-side LIST bubble rendering (ListBubble).
// endregion

package com.firestream.chat.data.repository

import android.util.Log
import com.firestream.chat.data.local.dao.ListDao
import com.firestream.chat.data.local.dao.MessageDao
import com.firestream.chat.data.local.entity.ListEntity
import com.firestream.chat.data.remote.firebase.FirebaseAuthSource
import com.firestream.chat.data.remote.firebase.FirestoreListHistorySource
import com.firestream.chat.data.remote.firebase.FirestoreListSource
import com.firestream.chat.data.util.resultOf
import com.firestream.chat.domain.model.GenericListStyle
import com.firestream.chat.domain.model.HistoryAction
import com.firestream.chat.domain.model.ListData
import com.firestream.chat.domain.model.ListHistoryEntry
import com.firestream.chat.domain.model.ListItem
import com.firestream.chat.domain.model.ListType
import com.firestream.chat.domain.model.Message
import com.firestream.chat.domain.repository.ChatRepository
import com.firestream.chat.domain.repository.ListRepository
import com.firestream.chat.domain.repository.MessageRepository
import com.firestream.chat.domain.repository.UserRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
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
    private var listSyncJob: kotlinx.coroutines.Job? = null

    // One mutex per list id — serialises intra-device read-modify-write sequences and
    // keeps history recording ordered with respect to the mutation it describes.
    private val listMutexes = ConcurrentHashMap<String, Mutex>()
    private fun mutexFor(listId: String): Mutex = listMutexes.getOrPut(listId) { Mutex() }

    // One-shot migration per list id. Mutations gate on this so a tap that lands before
    // the embedded→subcollection split finishes can't `batch.update` a not-yet-existing
    // item doc (which Firestore rejects with NOT_FOUND).
    private val migrationJobs = ConcurrentHashMap<String, Deferred<Unit>>()
    private fun ensureMigrated(listId: String): Deferred<Unit> =
        migrationJobs.getOrPut(listId) {
            historyScope.async {
                runCatching { listSource.migrateEmbeddedItemsIfNeeded(listId) }
                    .onFailure { Log.w("ListRepo", "migrateEmbeddedItemsIfNeeded failed for $listId", it) }
                Unit
            }
        }

    /** Keeps Room in sync with Firestore regardless of which screen is active. */
    private fun ensureListSyncRunning() {
        val userId = authSource.currentUserId ?: return
        if (listSyncJob?.isActive == true) return
        listSyncJob = historyScope.launch {
            while (coroutineContext[kotlinx.coroutines.Job]?.isActive == true) {
                try {
                    listSource.observeMyLists(userId).collectLatest { lists ->
                        // Per-list mutex: the read-merge-write must be atomic w.r.t.
                        // observeList's items listener. Otherwise a fresh item write from
                        // that listener can be stomped here by stale cached items, and
                        // the receiver never sees the new item.
                        lists.forEach { list ->
                            mutexFor(list.id).withLock {
                                val merged = mergeWithCachedItems(list)
                                listDao.insert(ListEntity.fromDomain(merged))
                            }
                        }
                        val ids = lists.map { it.id }
                        if (ids.isEmpty()) {
                            listDao.deleteAllForUser(userId)
                        } else {
                            listDao.deleteUnlistedForUser(ids, userId)
                        }
                    }
                } catch (_: Exception) { }
                kotlinx.coroutines.delay(3000)
            }
        }
    }

    /**
     * When metadata-only [ListData] comes from `observeMyLists` (post-migration), keep any
     * items already cached in Room so the detail screen can still show them offline.
     */
    private suspend fun mergeWithCachedItems(fresh: ListData): ListData {
        if (fresh.items.isNotEmpty()) return fresh
        val cached = listDao.getById(fresh.id)?.toDomain() ?: return fresh
        return fresh.copy(items = cached.items)
    }

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
        ensureListSyncRunning()

        // Kick off the one-shot migration (idempotent — subsequent calls return the
        // cached Deferred). Mutations await the same job so they can't race ahead of it.
        ensureMigrated(listId)

        // Metadata → Room. Two independent listeners (metadata + items) write into Room
        // instead of one combined listener so that each source can land in Room without
        // waiting on the other — matches how the rest of the codebase treats Room as the
        // single source of truth that the UI observes.
        launch {
            try {
                listSource.observeList(listId).collectLatest { listData ->
                    if (listData == null) {
                        listDao.delete(listId)
                        return@collectLatest
                    }
                    val userId = authSource.currentUserId
                    if (userId != null
                        && listData.participants.isNotEmpty()
                        && userId !in listData.participants
                        && userId != listData.createdBy
                    ) {
                        listDao.delete(listId)
                        return@collectLatest
                    }
                    // Per-list mutex: read cached items + write full entity must be atomic
                    // w.r.t. the items listener. Without it, a fresh items write can be
                    // stomped by this metadata write carrying stale cached items.
                    mutexFor(listId).withLock {
                        val cachedItems = listDao.getById(listId)?.toDomain()?.items.orEmpty()
                        val itemsToStore = listData.items.ifEmpty { cachedItems }
                        listDao.insert(ListEntity.fromDomain(listData.copy(items = itemsToStore)))
                    }
                }
            } catch (_: Exception) { }
        }

        // Items subcollection → patch Room's items blob in place. Only runs once the
        // metadata listener has written an entity for this list; otherwise we'd create a
        // phantom entity with empty metadata.
        launch {
            try {
                listSource.observeItems(listId).collectLatest { items ->
                    mutexFor(listId).withLock {
                        val existing = listDao.getById(listId) ?: return@withLock
                        val current = existing.toDomain()
                        if (current.items == items) return@withLock
                        listDao.insert(ListEntity.fromDomain(current.copy(items = items)))
                    }
                }
            } catch (_: Exception) { }
        }

        listDao.observeById(listId)
            .map { it?.toDomain() }
            .collect { send(it) }
    }

    override fun observeMyLists(): Flow<List<ListData>> {
        ensureListSyncRunning()
        val userId = authSource.currentUserId ?: return kotlinx.coroutines.flow.flowOf(emptyList())
        return listDao.getListsForUser(userId).map { entities -> entities.map { it.toDomain() } }
    }

    override suspend fun createList(title: String, type: ListType, chatId: String?, genericStyle: GenericListStyle): Result<ListData> = resultOf {
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

        if (chatId != null) {
            val chat = chatRepository.get().getChatById(chatId).getOrThrow()
            listSource.shareList(remoteId, chat.participants, chatId)
            listDao.updateSharedChatIds(remoteId, org.json.JSONArray(listOf(chatId)).toString(), System.currentTimeMillis())
            messageRepository.get().sendListMessage(chatId, remoteId, title, com.firestream.chat.domain.model.ListDiff(shared = true))
        }

        created
    }

    override suspend fun addItem(listId: String, itemId: String, text: String, quantity: String?, unit: String?): Result<Unit> = resultOf {
        val userId = authSource.currentUserId ?: throw Exception("Not authenticated")
        ensureMigrated(listId).await()
        mutexFor(listId).withLock {
            val entity = listDao.getById(listId)
            val existing = entity?.toDomain()?.items.orEmpty()
            // Use max(order)+1, not size: deletes leave gaps, so size can collide with a
            // still-living item's order. Firestore orderBy("order") breaks ties by document
            // id, so colliding new items land in random positions on the receiver's side.
            val nextOrder = (existing.maxOfOrNull { it.order } ?: -1) + 1
            val item = ListItem(
                id = itemId,
                text = text,
                quantity = quantity,
                unit = unit,
                order = nextOrder,
                addedBy = userId
            )
            // Room-first: subsequent same-client reads see the new item.
            entity?.let {
                val list = it.toDomain()
                listDao.insert(ListEntity.fromDomain(list.copy(
                    items = list.items + item,
                    itemCount = list.itemCount + 1,
                    updatedAt = System.currentTimeMillis()
                )))
            }
            listSource.addItem(listId, item)
            recordHistory(listId, HistoryAction.ITEM_ADDED, itemId = item.id, itemText = text)
        }
    }

    override suspend fun removeItem(listId: String, itemId: String): Result<Unit> = resultOf {
        ensureMigrated(listId).await()
        mutexFor(listId).withLock {
            val entity = listDao.getById(listId) ?: throw Exception("List not found")
            val list = entity.toDomain()
            val item = list.items.find { it.id == itemId } ?: throw Exception("Item not found")
            val remaining = list.items.filter { it.id != itemId }
            listDao.insert(ListEntity.fromDomain(list.copy(
                items = remaining,
                itemCount = (list.itemCount - 1).coerceAtLeast(0),
                checkedCount = (list.checkedCount - if (item.isChecked) 1 else 0).coerceAtLeast(0),
                updatedAt = System.currentTimeMillis()
            )))
            listSource.deleteItem(listId, itemId, wasChecked = item.isChecked)
            recordHistory(listId, HistoryAction.ITEM_REMOVED, itemId = itemId, itemText = item.text)
        }
    }

    override suspend fun clearCheckedItems(listId: String): Result<List<String>> = resultOf {
        ensureMigrated(listId).await()
        mutexFor(listId).withLock {
            val entity = listDao.getById(listId) ?: throw Exception("List not found")
            val list = entity.toDomain()
            val checkedItems = list.items.filter { it.isChecked }
            if (checkedItems.isEmpty()) return@withLock emptyList()
            val remaining = list.items.filter { !it.isChecked }
            listDao.insert(ListEntity.fromDomain(list.copy(
                items = remaining,
                itemCount = (list.itemCount - checkedItems.size).coerceAtLeast(0),
                checkedCount = 0,
                updatedAt = System.currentTimeMillis()
            )))
            listSource.clearCheckedItems(listId, checkedItems.map { it.id })
            checkedItems.forEach { item ->
                recordHistory(listId, HistoryAction.ITEM_REMOVED, itemId = item.id, itemText = item.text)
            }
            checkedItems.map { it.text }
        }
    }

    override suspend fun toggleItemChecked(listId: String, itemId: String, checked: Boolean): Result<Unit> = resultOf {
        ensureMigrated(listId).await()
        mutexFor(listId).withLock {
            val entity = listDao.getById(listId) ?: throw Exception("List not found")
            val list = entity.toDomain()
            val targetItem = list.items.find { it.id == itemId } ?: throw Exception("Item not found")
            // Idempotent: if Room already reflects the target state, still write through to
            // Firestore so the intent propagates to other devices even if we missed a tap echo.
            val updatedItems = list.items.map { i ->
                if (i.id == itemId) i.copy(isChecked = checked) else i
            }
            val checkedDelta = when {
                targetItem.isChecked == checked -> 0
                checked -> 1
                else -> -1
            }
            listDao.insert(ListEntity.fromDomain(list.copy(
                items = updatedItems,
                checkedCount = (list.checkedCount + checkedDelta).coerceIn(0, list.itemCount),
                updatedAt = System.currentTimeMillis()
            )))
            listSource.setItemChecked(listId, itemId, checked)
            val action = if (checked) HistoryAction.ITEM_CHECKED else HistoryAction.ITEM_UNCHECKED
            recordHistory(listId, action, itemId = itemId, itemText = targetItem.text)
        }
    }

    override suspend fun updateItem(
        listId: String,
        itemId: String,
        text: String,
        quantity: String?,
        unit: String?
    ): Result<Unit> = resultOf {
        ensureMigrated(listId).await()
        mutexFor(listId).withLock {
            val entity = listDao.getById(listId) ?: throw Exception("List not found")
            val list = entity.toDomain()
            val updatedItems = list.items.map { item ->
                if (item.id == itemId) item.copy(text = text, quantity = quantity, unit = unit) else item
            }
            listDao.insert(ListEntity.fromDomain(list.copy(
                items = updatedItems,
                updatedAt = System.currentTimeMillis()
            )))
            listSource.updateItem(listId, itemId, text, quantity, unit)
            recordHistory(listId, HistoryAction.ITEM_MODIFIED, itemId = itemId, itemText = text)
        }
    }

    override suspend fun reorderItems(listId: String, items: List<ListItem>): Result<Unit> = resultOf {
        ensureMigrated(listId).await()
        mutexFor(listId).withLock {
            val entity = listDao.getById(listId)
            if (entity != null) {
                val reordered = items.mapIndexed { idx, item -> item.copy(order = idx) }
                listDao.insert(ListEntity.fromDomain(entity.toDomain().copy(
                    items = reordered,
                    updatedAt = System.currentTimeMillis()
                )))
            }
            listSource.reorderItems(listId, items.map { it.id })
        }
    }

    override suspend fun updateListTitle(listId: String, title: String): Result<Unit> = resultOf {
        listSource.updateListTitle(listId, title)
        recordHistory(listId, HistoryAction.TITLE_CHANGED, itemText = title)
    }

    override suspend fun updateListType(listId: String, type: ListType): Result<Unit> = resultOf {
        listSource.updateListType(listId, type)
    }

    override suspend fun updateGenericStyle(listId: String, style: GenericListStyle): Result<Unit> = resultOf {
        listSource.updateGenericStyle(listId, style)
    }

    override suspend fun deleteList(listId: String): Result<Unit> = resultOf {
        listSource.deleteList(listId)
        listDao.delete(listId)
    }

    override suspend fun shareListToChat(listId: String, chatId: String): Result<Message> = resultOf {
        val entity = listDao.getById(listId) ?: throw Exception("List not found")
        val list = entity.toDomain()

        val chat = chatRepository.get().getChatById(chatId).getOrThrow()
        listSource.shareList(listId, chat.participants, chatId)

        val now = System.currentTimeMillis()
        val updatedIds = (list.sharedChatIds + chatId).distinct()
        listDao.updateSharedChatIds(listId, org.json.JSONArray(updatedIds).toString(), now)

        messageRepository.get()
            .sendListMessage(chatId, listId, list.title, com.firestream.chat.domain.model.ListDiff(shared = true))
            .getOrThrow()
    }

    override fun getSharedListsForChat(chatId: String): Flow<List<ListData>> = channelFlow {
        launch {
            try {
                listSource.observeSharedListsForChat(chatId).collectLatest { lists ->
                    // Cache received lists locally so detail screens work offline.
                    // Use merge to preserve any subcollection-sourced items already cached.
                    val merged = lists.map { mergeWithCachedItems(it) }
                    listDao.insertAll(merged.map { ListEntity.fromDomain(it) })
                    send(lists)
                }
            } catch (_: Exception) { }
        }
        awaitClose()
    }

    override fun observeHistory(listId: String): Flow<List<ListHistoryEntry>> =
        historySource.observeHistory(listId)

    override suspend fun addHistoryEntry(listId: String, entry: ListHistoryEntry): Result<Unit> = resultOf {
        historySource.addEntry(listId, entry)
    }

    override suspend fun updateSharedChatIds(listId: String, chatId: String): Result<Unit> = resultOf {
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
    }

    override suspend fun unshareListFromChat(listId: String, chatId: String): Result<Unit> = resultOf {
        val entity = listDao.getById(listId) ?: throw Exception("List not found")
        val list = entity.toDomain()

        val chat = chatRepository.get().getChatById(chatId).getOrThrow()
        val toRemove = chat.participants.filter { it != list.createdBy }

        listSource.unshareList(listId, toRemove, chatId)

        val now = System.currentTimeMillis()
        val arr = org.json.JSONArray(entity.sharedChatIds)
        val updatedIds = List(arr.length()) { i -> arr.getString(i) }.filter { it != chatId }
        listDao.updateSharedChatIds(listId, org.json.JSONArray(updatedIds).toString(), now)

        messageRepository.get().sendListMessage(
            chatId, listId, list.title,
            com.firestream.chat.domain.model.ListDiff(unshared = true)
        )
    }

    override suspend fun removeParticipant(listId: String, userId: String): Result<Unit> = resultOf {
        listSource.removeParticipant(listId, userId)
    }

    override suspend fun removeParticipants(listId: String, userIds: List<String>): Result<Unit> = resultOf {
        listSource.removeParticipants(listId, userIds)
    }

    override suspend fun fetchAndCacheList(listId: String) {
        try {
            val listData = listSource.getList(listId)
            if (listData != null) {
                listDao.insert(ListEntity.fromDomain(mergeWithCachedItems(listData)))
            } else {
                listDao.delete(listId)
            }
        } catch (_: Exception) { }
    }
}
