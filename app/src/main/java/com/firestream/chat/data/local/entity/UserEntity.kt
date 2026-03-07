package com.firestream.chat.data.local.entity

import androidx.room.Entity
import androidx.room.PrimaryKey
import com.firestream.chat.domain.model.User

@Entity(tableName = "users")
data class UserEntity(
    @PrimaryKey val uid: String,
    val phoneNumber: String,
    val displayName: String,
    val avatarUrl: String?,
    val statusText: String,
    val lastSeen: Long,
    val isOnline: Boolean,
    val publicIdentityKey: String
) {
    fun toDomain() = User(
        uid = uid,
        phoneNumber = phoneNumber,
        displayName = displayName,
        avatarUrl = avatarUrl,
        statusText = statusText,
        lastSeen = lastSeen,
        isOnline = isOnline,
        publicIdentityKey = publicIdentityKey
    )

    companion object {
        fun fromDomain(user: User) = UserEntity(
            uid = user.uid,
            phoneNumber = user.phoneNumber,
            displayName = user.displayName,
            avatarUrl = user.avatarUrl,
            statusText = user.statusText,
            lastSeen = user.lastSeen,
            isOnline = user.isOnline,
            publicIdentityKey = user.publicIdentityKey
        )
    }
}
