package com.firestream.chat.data.local.entity

import androidx.room.Entity
import androidx.room.PrimaryKey
import com.firestream.chat.domain.model.Contact

@Entity(tableName = "contacts")
data class ContactEntity(
    @PrimaryKey val uid: String,
    val phoneNumber: String,
    val displayName: String,
    val avatarUrl: String?,
    val isRegistered: Boolean,
    val cachedAvatarUrl: String? = null,
    val localAvatarPath: String? = null
) {
    fun toDomain() = Contact(
        uid = uid,
        phoneNumber = phoneNumber,
        displayName = displayName,
        avatarUrl = avatarUrl,
        localAvatarPath = localAvatarPath,
        isRegistered = isRegistered
    )

    companion object {
        fun fromDomain(contact: Contact) = ContactEntity(
            uid = contact.uid,
            phoneNumber = contact.phoneNumber,
            displayName = contact.displayName,
            avatarUrl = contact.avatarUrl,
            isRegistered = contact.isRegistered
        )
    }
}
