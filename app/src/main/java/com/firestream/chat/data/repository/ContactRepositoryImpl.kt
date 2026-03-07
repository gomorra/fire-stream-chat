package com.firestream.chat.data.repository

import com.firestream.chat.data.local.dao.ContactDao
import com.firestream.chat.data.local.entity.ContactEntity
import com.firestream.chat.domain.model.Contact
import com.firestream.chat.domain.repository.ContactRepository
import com.google.firebase.firestore.FirebaseFirestore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.tasks.await
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ContactRepositoryImpl @Inject constructor(
    private val contactDao: ContactDao,
    private val firestore: FirebaseFirestore
) : ContactRepository {

    override fun getContacts(): Flow<List<Contact>> {
        return contactDao.getRegisteredContacts().map { entities ->
            entities.map { it.toDomain() }
        }
    }

    override suspend fun syncContacts(): Result<List<Contact>> {
        return try {
            // Query all registered users from Firestore
            // In production, this would check against the device's contact list
            val snapshot = firestore.collection("users").get().await()
            val contacts = snapshot.documents.mapNotNull { doc ->
                val data = doc.data ?: return@mapNotNull null
                Contact(
                    uid = doc.id,
                    phoneNumber = data["phoneNumber"] as? String ?: "",
                    displayName = data["displayName"] as? String ?: "",
                    avatarUrl = data["avatarUrl"] as? String,
                    isRegistered = true
                )
            }
            contactDao.insertContacts(contacts.map { ContactEntity.fromDomain(it) })
            Result.success(contacts)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    override suspend fun searchContacts(query: String): List<Contact> {
        return contactDao.searchContacts(query).map { it.toDomain() }
    }
}
