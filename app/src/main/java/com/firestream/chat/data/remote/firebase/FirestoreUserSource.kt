package com.firestream.chat.data.remote.firebase

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
) {
    fun observeUser(userId: String): Flow<User> = callbackFlow {
        val listener: ListenerRegistration = firestore.collection("users")
            .document(userId)
            .addSnapshotListener { snapshot, error ->
                if (error != null) {
                    close(error)
                    return@addSnapshotListener
                }
                snapshot?.data?.let { data ->
                    trySend(mapToUser(data))
                }
            }
        awaitClose { listener.remove() }
    }

    suspend fun getUserById(userId: String): User? {
        val doc = firestore.collection("users").document(userId).get().await()
        return if (doc.exists()) doc.data?.let { mapToUser(it) } else null
    }

    suspend fun updateProfile(userId: String, updates: Map<String, Any?>) {
        val filtered = updates.filterValues { it != null }
        if (filtered.isNotEmpty()) {
            firestore.collection("users").document(userId).update(filtered).await()
        }
    }

    suspend fun setOnlineStatus(userId: String, isOnline: Boolean) {
        firestore.collection("users").document(userId).update(
            mapOf(
                "isOnline" to isOnline,
                "lastSeen" to System.currentTimeMillis()
            )
        ).await()
    }

    private fun mapToUser(data: Map<String, Any?>): User {
        return User(
            uid = data["uid"] as? String ?: "",
            phoneNumber = data["phoneNumber"] as? String ?: "",
            displayName = data["displayName"] as? String ?: "",
            avatarUrl = data["avatarUrl"] as? String,
            statusText = data["statusText"] as? String ?: "",
            lastSeen = data["lastSeen"] as? Long ?: 0L,
            isOnline = data["isOnline"] as? Boolean ?: false,
            publicIdentityKey = data["publicIdentityKey"] as? String ?: ""
        )
    }
}
