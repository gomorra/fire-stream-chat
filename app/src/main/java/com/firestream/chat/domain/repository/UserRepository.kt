package com.firestream.chat.domain.repository

import com.firestream.chat.domain.model.User
import kotlinx.coroutines.flow.Flow

interface UserRepository {
    fun observeUser(userId: String): Flow<User>
    suspend fun getUserById(userId: String): Result<User>
    /** @param uri URI string (e.g. `content://...` or `file://...`). Parsed in the data layer. */
    suspend fun uploadAvatar(uri: String): Result<String>
    suspend fun updateProfile(displayName: String?, statusText: String?, avatarUrl: String?): Result<Unit>
    suspend fun setOnlineStatus(isOnline: Boolean): Result<Unit>
    suspend fun updateLastSeen(): Result<Unit>
    suspend fun blockUser(userId: String): Result<Unit>
    suspend fun unblockUser(userId: String): Result<Unit>
    suspend fun isUserBlocked(userId: String): Boolean
    suspend fun updateReadReceipts(enabled: Boolean): Result<Unit>
}
