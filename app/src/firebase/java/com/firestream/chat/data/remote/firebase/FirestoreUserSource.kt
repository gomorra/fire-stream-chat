package com.firestream.chat.data.remote.firebase

import com.firestream.chat.data.remote.source.UserSource
import com.firestream.chat.domain.model.User
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.ListenerRegistration
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.tasks.await
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class FirestoreUserSource @Inject constructor(
    private val firestore: FirebaseFirestore
) : UserSource {
    override fun observeUser(userId: String): Flow<User> = callbackFlow {
        val listener: ListenerRegistration = firestore.collection("users")
            .document(userId)
            .addSnapshotListener { snapshot, error ->
                if (error != null) {
                    close(error)
                    return@addSnapshotListener
                }
                snapshot?.takeIf { it.exists() }?.let { doc ->
                    trySend(mapToUser(doc.data ?: return@let, doc.id))
                }
            }
        awaitClose { listener.remove() }
    }

    override suspend fun getUserById(userId: String): User? {
        val doc = firestore.collection("users").document(userId).get().await()
        return if (doc.exists()) doc.data?.let { mapToUser(it, doc.id) } else null
    }

    override suspend fun updateProfile(userId: String, updates: Map<String, Any?>) {
        val filtered = updates.filterValues { it != null }
        if (filtered.isNotEmpty()) {
            firestore.collection("users").document(userId).update(filtered).await()
        }
    }

    override suspend fun setOnlineStatus(userId: String, isOnline: Boolean) {
        firestore.collection("users").document(userId).update(
            mapOf(
                "isOnline" to isOnline,
                "lastSeen" to System.currentTimeMillis()
            )
        ).await()
    }

    override suspend fun blockUser(currentUserId: String, targetUserId: String) {
        firestore.collection("users").document(currentUserId)
            .collection("blockedUsers").document(targetUserId)
            .set(mapOf("blockedAt" to System.currentTimeMillis()))
            .await()
    }

    override suspend fun unblockUser(currentUserId: String, targetUserId: String) {
        firestore.collection("users").document(currentUserId)
            .collection("blockedUsers").document(targetUserId)
            .delete()
            .await()
    }

    override suspend fun isUserBlocked(currentUserId: String, targetUserId: String): Boolean {
        val doc = firestore.collection("users").document(currentUserId)
            .collection("blockedUsers").document(targetUserId)
            .get()
            .await()
        return doc.exists()
    }

    override suspend fun getBlockedUserIds(userId: String): Set<String> {
        val snapshot = firestore.collection("users").document(userId)
            .collection("blockedUsers").get().await()
        return snapshot.documents.mapTo(mutableSetOf()) { it.id }
    }

    private fun mapToUser(data: Map<String, Any?>, documentId: String = ""): User {
        return User(
            uid = (data["uid"] as? String)?.takeIf { it.isNotBlank() } ?: documentId,
            phoneNumber = data["phoneNumber"] as? String ?: "",
            displayName = data["displayName"] as? String ?: "",
            avatarUrl = data["avatarUrl"] as? String,
            statusText = data["statusText"] as? String ?: "",
            lastSeen = data["lastSeen"] as? Long ?: 0L,
            isOnline = data["isOnline"] as? Boolean ?: false,
            publicIdentityKey = data["publicIdentityKey"] as? String ?: "",
            readReceiptsEnabled = data["readReceiptsEnabled"] as? Boolean ?: true
        )
    }
}
