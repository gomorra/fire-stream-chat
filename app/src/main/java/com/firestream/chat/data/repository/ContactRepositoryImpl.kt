package com.firestream.chat.data.repository

import com.firestream.chat.data.local.dao.ContactDao
import com.firestream.chat.data.local.entity.ContactEntity
import com.firestream.chat.data.remote.source.ContactSource
import com.firestream.chat.data.util.ProfileImageManager
import com.firestream.chat.domain.model.Contact
import com.firestream.chat.domain.repository.ContactRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ContactRepositoryImpl @Inject constructor(
    private val contactDao: ContactDao,
    private val contactSource: ContactSource,
    private val profileImageManager: ProfileImageManager
) : ContactRepository {

    override fun getContacts(): Flow<List<Contact>> {
        return contactDao.getRegisteredContacts().map { entities ->
            entities.map { entity ->
                val domain = entity.toDomain()
                // Verify local file still exists
                val localPath = if (entity.localAvatarPath != null && profileImageManager.fileExists(entity.uid)) {
                    entity.localAvatarPath
                } else null
                domain.copy(localAvatarPath = localPath)
            }
        }
    }

    override suspend fun syncContacts(): Result<List<Contact>> {
        return try {
            // Query all registered users from the backend.
            // In production, this would check against the device's contact list.
            val contacts = contactSource.fetchAllRegisteredContacts()

            // Preserve existing avatar cache fields during bulk insert
            val existingMap = contactDao.getAllContactsSync().associateBy { it.uid }
            val entities = contacts.map { contact ->
                val existing = existingMap[contact.uid]
                val entity = ContactEntity.fromDomain(contact)
                if (existing != null) {
                    entity.copy(
                        cachedAvatarUrl = existing.cachedAvatarUrl,
                        localAvatarPath = existing.localAvatarPath
                    )
                } else entity
            }
            contactDao.insertContacts(entities)

            // Download avatars for contacts whose URL changed
            for (contact in contacts) {
                val existing = existingMap[contact.uid]
                if (contact.avatarUrl != null) {
                    val needsDownload = contact.avatarUrl != existing?.cachedAvatarUrl ||
                        existing?.localAvatarPath == null ||
                        !profileImageManager.fileExists(contact.uid)
                    if (needsDownload) {
                        try {
                            val file = profileImageManager.downloadAvatar(contact.uid, contact.avatarUrl)
                            contactDao.updateAvatarCache(contact.uid, contact.avatarUrl, file.absolutePath)
                        } catch (_: Exception) { /* Will retry on next sync */ }
                    }
                } else if (existing?.localAvatarPath != null) {
                    profileImageManager.deleteAvatar(contact.uid)
                    contactDao.updateAvatarCache(contact.uid, null, null)
                }
            }

            Result.success(contacts)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    override suspend fun searchContacts(query: String): List<Contact> {
        return contactDao.searchContacts(query).map { it.toDomain() }
    }
}
