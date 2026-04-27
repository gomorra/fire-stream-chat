package com.firestream.chat.data.remote.firebase

import com.firestream.chat.data.remote.source.ContactSource
import com.firestream.chat.domain.model.Contact
import com.google.firebase.firestore.FirebaseFirestore
import kotlinx.coroutines.tasks.await
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Firestore-backed [ContactSource]. Extracted from `ContactRepositoryImpl` in
 * step 3c so the repo can stay backend-neutral.
 */
@Singleton
class FirestoreContactSource @Inject constructor(
    private val firestore: FirebaseFirestore
) : ContactSource {

    override suspend fun fetchAllRegisteredContacts(): List<Contact> {
        val snapshot = firestore.collection("users").get().await()
        return snapshot.documents.mapNotNull { doc ->
            val data = doc.data ?: return@mapNotNull null
            Contact(
                uid = doc.id,
                phoneNumber = data["phoneNumber"] as? String ?: "",
                displayName = data["displayName"] as? String ?: "",
                avatarUrl = data["avatarUrl"] as? String,
                isRegistered = true
            )
        }
    }
}
