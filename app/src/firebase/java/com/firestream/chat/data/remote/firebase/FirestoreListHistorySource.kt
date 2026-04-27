// region: AGENT-NOTE
// Responsibility: Audit-trail subcollection at `lists/{listId}/history/{entryId}`.
//   Each list mutation (add/check/edit/remove/title-change/type-change/share/
//   unshare) writes one entry with action, itemId, userId, userName, timestamp.
// Owns: Listener registrations on `lists/{id}/history`, ordered by timestamp.
// Collaborators: ListRepositoryImpl (caller; writes are fire-and-forget on a
//   SupervisorJob so they never block the main flow).
// Don't put here: list/item state itself (FirestoreListSource); business rules
//   for which mutations get audited (ListRepositoryImpl decides).
// endregion

package com.firestream.chat.data.remote.firebase

import com.firestream.chat.data.remote.source.ListHistorySource
import com.firestream.chat.domain.model.HistoryAction
import com.firestream.chat.domain.model.ListHistoryEntry
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
class FirestoreListHistorySource @Inject constructor(
    private val firestore: FirebaseFirestore
) : ListHistorySource {
    private fun historyCollection(listId: String) =
        firestore.collection("lists").document(listId).collection("history")

    override suspend fun addEntry(listId: String, entry: ListHistoryEntry) {
        val data = hashMapOf(
            "action" to entry.action.name,
            "itemId" to entry.itemId,
            "itemText" to entry.itemText,
            "userId" to entry.userId,
            "userName" to entry.userName,
            "timestamp" to entry.timestamp
        )
        historyCollection(listId).add(data).await()
    }

    override fun observeHistory(listId: String): Flow<List<ListHistoryEntry>> = callbackFlow {
        val listener: ListenerRegistration = historyCollection(listId)
            .orderBy("timestamp", Query.Direction.DESCENDING)
            .addSnapshotListener { snapshot, error ->
                if (error != null) {
                    close(error)
                    return@addSnapshotListener
                }
                val entries = snapshot?.documents?.mapNotNull { doc ->
                    doc.data?.let { mapToEntry(doc.id, it) }
                } ?: emptyList()
                trySend(entries)
            }
        awaitClose { listener.remove() }
    }

    private fun mapToEntry(id: String, data: Map<String, Any?>): ListHistoryEntry = ListHistoryEntry(
        id = id,
        action = runCatching { HistoryAction.valueOf(data["action"] as? String ?: "") }
            .getOrDefault(HistoryAction.CREATED),
        itemId = data["itemId"] as? String,
        itemText = data["itemText"] as? String,
        userId = data["userId"] as? String ?: "",
        userName = data["userName"] as? String ?: "",
        timestamp = data["timestamp"] as? Long ?: 0L
    )
}
