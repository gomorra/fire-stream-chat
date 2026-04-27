package com.firestream.chat.data.remote.pocketbase

import com.firestream.chat.data.remote.source.UserSource
import com.firestream.chat.domain.model.User
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import javax.inject.Inject
import javax.inject.Singleton

/** Step 4 stub. Real impl in step 6. */
@Singleton
class PocketBaseUserSource @Inject constructor() : UserSource {
    override fun observeUser(userId: String): Flow<User> = emptyFlow()

    override suspend fun getUserById(userId: String): User? =
        throw NotImplementedError("PB v0 stub")

    override suspend fun updateProfile(userId: String, updates: Map<String, Any?>): Unit =
        throw NotImplementedError("PB v0 stub")

    override suspend fun setOnlineStatus(userId: String, isOnline: Boolean): Unit =
        throw NotImplementedError("PB v0 stub")

    override suspend fun blockUser(currentUserId: String, targetUserId: String): Unit =
        throw NotImplementedError("PB v0 stub")

    override suspend fun unblockUser(currentUserId: String, targetUserId: String): Unit =
        throw NotImplementedError("PB v0 stub")

    override suspend fun isUserBlocked(currentUserId: String, targetUserId: String): Boolean = false

    override suspend fun getBlockedUserIds(userId: String): Set<String> = emptySet()
}
