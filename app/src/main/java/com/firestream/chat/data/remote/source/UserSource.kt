package com.firestream.chat.data.remote.source

import com.firestream.chat.domain.model.User
import kotlinx.coroutines.flow.Flow

/** Backend-neutral user-profile + block-list boundary. */
interface UserSource {
    fun observeUser(userId: String): Flow<User>
    suspend fun getUserById(userId: String): User?
    suspend fun updateProfile(userId: String, updates: Map<String, Any?>)
    suspend fun setOnlineStatus(userId: String, isOnline: Boolean)
    suspend fun blockUser(currentUserId: String, targetUserId: String)
    suspend fun unblockUser(currentUserId: String, targetUserId: String)
    suspend fun isUserBlocked(currentUserId: String, targetUserId: String): Boolean
    suspend fun getBlockedUserIds(userId: String): Set<String>
}
