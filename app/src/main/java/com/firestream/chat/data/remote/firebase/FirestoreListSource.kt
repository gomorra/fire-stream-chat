// region: AGENT-NOTE
// Responsibility: Firestore I/O for `lists/{listId}` metadata doc + items
//   subcollection at `lists/{listId}/items/{itemId}`. Maintains denormalized
//   `itemCount` / `checkedCount` on the parent doc via FieldValue.increment in
//   the same batch as item writes. One-shot legacy migration for pre-refactor
//   embedded `items` arrays (migrateEmbeddedItemsIfNeeded).
// Owns: Listener registrations on `lists/*` and items subcollection.
// Collaborators: ListRepositoryImpl (only caller).
// Don't put here: history audit trail (FirestoreListHistorySource), share-state
//   coordination with chats (ListRepositoryImpl handles sharedChatIds + LIST
//   message creation).
// endregion

package com.firestream.chat.data.remote.firebase

import com.firestream.chat.domain.model.GenericListStyle
import com.firestream.chat.domain.model.ListData
import com.firestream.chat.domain.model.ListItem
import com.firestream.chat.domain.model.ListType
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.ListenerRegistration
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.tasks.await
import javax.inject.Inject
import javax.inject.Singleton

/**
 * List items live in a per-list subcollection (`lists/{id}/items/{itemId}`) so that each
 * mutation is a single-document write — trivially atomic, safely concurrent across clients,
 * and offline-safe. Denormalized `itemCount` / `checkedCount` are maintained on the parent
 * metadata doc via [FieldValue.increment] in the same batch as the item write, so the Lists
 * tab can show "N items, M checked" without subscribing to every list's subcollection.
 *
 * Older lists created before this refactor had an embedded `items` array field; see
 * [migrateEmbeddedItemsIfNeeded] for the one-shot migration run on first observe.
 */
@Singleton
class FirestoreListSource @Inject constructor(
    private val firestore: FirebaseFirestore
) {
    private val listsCollection get() = firestore.collection("lists")
    private fun itemsCollection(listId: String) = listsCollection.document(listId).collection("items")

    /** Emits list metadata (items arrive via [observeItems]). */
    fun observeList(listId: String): Flow<ListData?> = callbackFlow {
        val listener: ListenerRegistration = listsCollection.document(listId)
            .addSnapshotListener { snapshot, error ->
                if (error != null) {
                    close(error)
                    return@addSnapshotListener
                }
                trySend(snapshot?.data?.let { mapToListData(listId, it) })
            }
        awaitClose { listener.remove() }
    }

    fun observeItems(listId: String): Flow<List<ListItem>> = callbackFlow {
        val listener: ListenerRegistration = itemsCollection(listId).orderBy("order")
            .addSnapshotListener { snapshot, error ->
                if (error != null) {
                    close(error)
                    return@addSnapshotListener
                }
                val items = snapshot?.documents?.mapNotNull { doc ->
                    doc.data?.let { mapToListItem(it + ("id" to doc.id)) }
                } ?: emptyList()
                trySend(items)
            }
        awaitClose { listener.remove() }
    }

    fun observeSharedListsForChat(chatId: String): Flow<List<ListData>> = callbackFlow {
        val listener: ListenerRegistration = listsCollection
            .whereArrayContains("sharedChatIds", chatId)
            .addSnapshotListener { snapshot, error ->
                if (error != null) {
                    close(error)
                    return@addSnapshotListener
                }
                val lists = snapshot?.documents?.mapNotNull { doc ->
                    doc.data?.let { mapToListData(doc.id, it) }
                } ?: emptyList()
                trySend(lists)
            }
        awaitClose { listener.remove() }
    }

    fun observeMyLists(userId: String): Flow<List<ListData>> = callbackFlow {
        val listener: ListenerRegistration = listsCollection
            .whereArrayContains("participants", userId)
            .addSnapshotListener { snapshot, error ->
                if (error != null) {
                    close(error)
                    return@addSnapshotListener
                }
                val lists = snapshot?.documents?.mapNotNull { doc ->
                    doc.data?.let { mapToListData(doc.id, it) }
                } ?: emptyList()
                trySend(lists)
            }
        awaitClose { listener.remove() }
    }

    suspend fun getList(listId: String): ListData? {
        val doc = listsCollection.document(listId).get().await()
        return doc.data?.let { mapToListData(listId, it) }
    }

    suspend fun createList(list: ListData): String {
        val data = hashMapOf(
            "title" to list.title,
            "type" to list.type.name,
            "createdBy" to list.createdBy,
            "createdAt" to list.createdAt,
            "updatedAt" to list.updatedAt,
            "participants" to list.participants,
            "itemCount" to 0,
            "checkedCount" to 0,
            "sharedChatIds" to list.sharedChatIds,
            "genericStyle" to list.genericStyle.name
        )
        val docRef = listsCollection.add(data).await()
        return docRef.id
    }

    // ── Item mutations (subcollection-based) ─────────────────────────

    suspend fun addItem(listId: String, item: ListItem) {
        val now = System.currentTimeMillis()
        firestore.runBatch { batch ->
            batch.set(itemsCollection(listId).document(item.id), itemToMap(item))
            batch.update(
                listsCollection.document(listId),
                mapOf(
                    "itemCount" to FieldValue.increment(1),
                    "checkedCount" to FieldValue.increment(if (item.isChecked) 1 else 0),
                    "updatedAt" to now
                )
            )
        }.await()
    }

    suspend fun setItemChecked(listId: String, itemId: String, checked: Boolean) {
        val now = System.currentTimeMillis()
        firestore.runBatch { batch ->
            batch.update(
                itemsCollection(listId).document(itemId),
                mapOf("isChecked" to checked, "updatedAt" to now)
            )
            batch.update(
                listsCollection.document(listId),
                mapOf(
                    "checkedCount" to FieldValue.increment(if (checked) 1 else -1),
                    "updatedAt" to now
                )
            )
        }.await()
    }

    suspend fun updateItem(listId: String, itemId: String, text: String, quantity: String?, unit: String?) {
        val now = System.currentTimeMillis()
        firestore.runBatch { batch ->
            batch.update(
                itemsCollection(listId).document(itemId),
                mapOf(
                    "text" to text,
                    "quantity" to quantity,
                    "unit" to unit,
                    "updatedAt" to now
                )
            )
            batch.update(listsCollection.document(listId), "updatedAt", now)
        }.await()
    }

    suspend fun deleteItem(listId: String, itemId: String, wasChecked: Boolean) {
        val now = System.currentTimeMillis()
        firestore.runBatch { batch ->
            batch.delete(itemsCollection(listId).document(itemId))
            batch.update(
                listsCollection.document(listId),
                mapOf(
                    "itemCount" to FieldValue.increment(-1),
                    "checkedCount" to FieldValue.increment(if (wasChecked) -1 else 0),
                    "updatedAt" to now
                )
            )
        }.await()
    }

    /** Batch-deletes the listed item ids and decrements itemCount by N, checkedCount by N. */
    suspend fun clearCheckedItems(listId: String, checkedItemIds: List<String>) {
        if (checkedItemIds.isEmpty()) return
        val now = System.currentTimeMillis()
        firestore.runBatch { batch ->
            checkedItemIds.forEach { id ->
                batch.delete(itemsCollection(listId).document(id))
            }
            batch.update(
                listsCollection.document(listId),
                mapOf(
                    "itemCount" to FieldValue.increment(-checkedItemIds.size.toLong()),
                    "checkedCount" to FieldValue.increment(-checkedItemIds.size.toLong()),
                    "updatedAt" to now
                )
            )
        }.await()
    }

    suspend fun reorderItems(listId: String, orderedIds: List<String>) {
        val now = System.currentTimeMillis()
        firestore.runBatch { batch ->
            orderedIds.forEachIndexed { index, id ->
                batch.update(itemsCollection(listId).document(id), "order", index)
            }
            batch.update(listsCollection.document(listId), "updatedAt", now)
        }.await()
    }

    /**
     * Migrate a pre-refactor list (embedded `items` array in the metadata doc) to the subcollection.
     * Idempotent: if the subcollection already has content, only the legacy `items` field is stripped.
     */
    suspend fun migrateEmbeddedItemsIfNeeded(listId: String) {
        val docRef = listsCollection.document(listId)
        val doc = docRef.get().await()
        @Suppress("UNCHECKED_CAST")
        val embedded = (doc.get("items") as? List<Map<String, Any?>>).orEmpty()
        if (embedded.isEmpty()) return

        val sub = itemsCollection(listId).limit(1).get().await()
        if (!sub.isEmpty) {
            // Subcollection already populated — just strip the legacy field.
            docRef.update("items", FieldValue.delete()).await()
            return
        }

        val items = embedded.map { mapToListItem(it) }
        val checked = items.count { it.isChecked }
        firestore.runBatch { batch ->
            items.forEach { item ->
                batch.set(itemsCollection(listId).document(item.id), itemToMap(item))
            }
            batch.update(
                docRef,
                mapOf(
                    "items" to FieldValue.delete(),
                    "itemCount" to items.size,
                    "checkedCount" to checked,
                    "updatedAt" to System.currentTimeMillis()
                )
            )
        }.await()
    }

    // ── List-level mutations (unchanged) ─────────────────────────────

    suspend fun deleteList(listId: String) {
        // Firestore does not cascade-delete subcollections. Best effort: drop items first
        // so a re-created list-id does not inherit stale items.
        runCatching {
            val items = itemsCollection(listId).get().await()
            if (!items.isEmpty) {
                firestore.runBatch { batch ->
                    items.documents.forEach { batch.delete(it.reference) }
                }.await()
            }
        }
        listsCollection.document(listId).delete().await()
    }

    suspend fun addParticipant(listId: String, userId: String) {
        listsCollection.document(listId).update(
            "participants", FieldValue.arrayUnion(userId)
        ).await()
    }

    suspend fun removeParticipant(listId: String, userId: String) {
        listsCollection.document(listId).update(
            "participants", FieldValue.arrayRemove(userId)
        ).await()
    }

    suspend fun removeParticipants(listId: String, userIds: List<String>) {
        listsCollection.document(listId).update(
            "participants", FieldValue.arrayRemove(*userIds.toTypedArray())
        ).await()
    }

    suspend fun updateListTitle(listId: String, title: String) {
        listsCollection.document(listId).update(
            mapOf(
                "title" to title,
                "updatedAt" to System.currentTimeMillis()
            )
        ).await()
    }

    suspend fun shareList(listId: String, participantIds: List<String>, chatId: String) {
        listsCollection.document(listId).update(mapOf(
            "participants" to FieldValue.arrayUnion(*participantIds.toTypedArray()),
            "sharedChatIds" to FieldValue.arrayUnion(chatId),
            "updatedAt" to System.currentTimeMillis()
        )).await()
    }

    suspend fun updateSharedChatIds(listId: String, chatId: String) {
        listsCollection.document(listId).update(
            "sharedChatIds", FieldValue.arrayUnion(chatId)
        ).await()
    }

    suspend fun unshareList(listId: String, participantIdsToRemove: List<String>, chatId: String) {
        val updates = mutableMapOf<String, Any>(
            "sharedChatIds" to FieldValue.arrayRemove(chatId)
        )
        if (participantIdsToRemove.isNotEmpty()) {
            updates["participants"] = FieldValue.arrayRemove(*participantIdsToRemove.toTypedArray())
        }
        listsCollection.document(listId).update(updates).await()
    }

    suspend fun updateListType(listId: String, type: ListType) {
        listsCollection.document(listId).update(
            mapOf(
                "type" to type.name,
                "updatedAt" to System.currentTimeMillis()
            )
        ).await()
    }

    suspend fun updateGenericStyle(listId: String, style: GenericListStyle) {
        listsCollection.document(listId).update(
            mapOf(
                "genericStyle" to style.name,
                "updatedAt" to System.currentTimeMillis()
            )
        ).await()
    }

    // ── Mapping ─────────────────────────────────────────────────────

    private fun itemToMap(item: ListItem): Map<String, Any?> = mapOf(
        "text" to item.text,
        "isChecked" to item.isChecked,
        "quantity" to item.quantity,
        "unit" to item.unit,
        "order" to item.order,
        "addedBy" to item.addedBy
    )

    @Suppress("UNCHECKED_CAST")
    private fun mapToListData(id: String, data: Map<String, Any?>): ListData {
        // Legacy docs carry an embedded `items` array. When present (pre-migration) fall back to
        // it so observeList still surfaces items; post-migration the field is absent.
        val legacyItemsRaw = (data["items"] as? List<Map<String, Any?>>) ?: emptyList()
        val legacyItems = legacyItemsRaw.map { mapToListItem(it) }
        val explicitItemCount = (data["itemCount"] as? Long)?.toInt()
        val explicitCheckedCount = (data["checkedCount"] as? Long)?.toInt()
        return ListData(
            id = id,
            title = data["title"] as? String ?: "",
            type = runCatching { ListType.valueOf(data["type"] as? String ?: "") }
                .getOrDefault(ListType.CHECKLIST),
            createdBy = data["createdBy"] as? String ?: "",
            createdAt = data["createdAt"] as? Long ?: 0L,
            updatedAt = data["updatedAt"] as? Long ?: 0L,
            participants = (data["participants"] as? List<*>)
                ?.filterIsInstance<String>() ?: emptyList(),
            items = legacyItems,
            itemCount = explicitItemCount ?: legacyItems.size,
            checkedCount = explicitCheckedCount ?: legacyItems.count { it.isChecked },
            sharedChatIds = (data["sharedChatIds"] as? List<*>)
                ?.filterIsInstance<String>() ?: emptyList(),
            genericStyle = runCatching { GenericListStyle.valueOf(data["genericStyle"] as? String ?: "") }
                .getOrDefault(GenericListStyle.BULLET)
        )
    }

    private fun mapToListItem(data: Map<String, Any?>): ListItem = ListItem(
        id = data["id"] as? String ?: "",
        text = data["text"] as? String ?: "",
        isChecked = data["isChecked"] as? Boolean ?: false,
        quantity = data["quantity"] as? String,
        unit = data["unit"] as? String,
        order = (data["order"] as? Long)?.toInt() ?: 0,
        addedBy = data["addedBy"] as? String ?: ""
    )
}
