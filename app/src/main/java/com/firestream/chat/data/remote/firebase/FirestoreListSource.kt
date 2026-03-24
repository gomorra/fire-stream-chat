package com.firestream.chat.data.remote.firebase

import com.firestream.chat.domain.model.ListData
import com.firestream.chat.domain.model.ListItem
import com.firestream.chat.domain.model.ListType
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.ListenerRegistration
import com.google.firebase.firestore.Query
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.tasks.await
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class FirestoreListSource @Inject constructor(
    private val firestore: FirebaseFirestore
) {
    private val listsCollection get() = firestore.collection("lists")

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

    fun observeMyLists(userId: String): Flow<List<ListData>> = callbackFlow {
        val listener: ListenerRegistration = listsCollection
            .whereArrayContains("participants", userId)
            .orderBy("updatedAt", Query.Direction.DESCENDING)
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

    suspend fun createList(list: ListData): String {
        val data = hashMapOf(
            "title" to list.title,
            "type" to list.type.name,
            "createdBy" to list.createdBy,
            "createdAt" to list.createdAt,
            "updatedAt" to list.updatedAt,
            "participants" to list.participants,
            "items" to list.items.map { itemToMap(it) },
            "sharedChatIds" to list.sharedChatIds
        )
        val docRef = listsCollection.add(data).await()
        return docRef.id
    }

    suspend fun updateListItems(listId: String, items: List<ListItem>) {
        listsCollection.document(listId).update(
            mapOf(
                "items" to items.map { itemToMap(it) },
                "updatedAt" to System.currentTimeMillis()
            )
        ).await()
    }

    suspend fun addItem(listId: String, item: ListItem) {
        listsCollection.document(listId).update(
            mapOf(
                "items" to FieldValue.arrayUnion(itemToMap(item)),
                "updatedAt" to System.currentTimeMillis()
            )
        ).await()
    }

    suspend fun removeItem(listId: String, item: ListItem) {
        listsCollection.document(listId).update(
            mapOf(
                "items" to FieldValue.arrayRemove(itemToMap(item)),
                "updatedAt" to System.currentTimeMillis()
            )
        ).await()
    }

    suspend fun deleteList(listId: String) {
        listsCollection.document(listId).delete().await()
    }

    suspend fun addParticipant(listId: String, userId: String) {
        listsCollection.document(listId).update(
            "participants", FieldValue.arrayUnion(userId)
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

    suspend fun updateSharedChatIds(listId: String, chatId: String) {
        listsCollection.document(listId).update(
            "sharedChatIds", FieldValue.arrayUnion(chatId)
        ).await()
    }

    suspend fun updateListType(listId: String, type: ListType) {
        listsCollection.document(listId).update(
            mapOf(
                "type" to type.name,
                "updatedAt" to System.currentTimeMillis()
            )
        ).await()
    }

    private fun itemToMap(item: ListItem): Map<String, Any?> = mapOf(
        "id" to item.id,
        "text" to item.text,
        "isChecked" to item.isChecked,
        "quantity" to item.quantity,
        "unit" to item.unit,
        "order" to item.order,
        "addedBy" to item.addedBy
    )

    @Suppress("UNCHECKED_CAST")
    private fun mapToListData(id: String, data: Map<String, Any?>): ListData {
        val rawItems = (data["items"] as? List<Map<String, Any?>>) ?: emptyList()
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
            items = rawItems.map { mapToListItem(it) },
            sharedChatIds = (data["sharedChatIds"] as? List<*>)
                ?.filterIsInstance<String>() ?: emptyList()
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
