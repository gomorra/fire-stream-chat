package com.firestream.chat.domain.repository

import com.firestream.chat.domain.model.User
import kotlinx.coroutines.flow.Flow

interface UserRepository {
    fun observeUser(userId: String): Flow<User>
    suspend fun getUserById(userId: String): Result<User>
    suspend fun updateProfile(displayName: String?, statusText: String?, avatarUrl: String?): Result<Unit>
    suspend fun setOnlineStatus(isOnline: Boolean): Result<Unit>
    suspend fun updateLastSeen(): Result<Unit>
}
