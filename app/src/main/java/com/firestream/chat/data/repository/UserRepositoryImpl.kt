package com.firestream.chat.data.repository

import com.firestream.chat.data.local.dao.UserDao
import com.firestream.chat.data.local.entity.UserEntity
import com.firestream.chat.data.remote.firebase.FirebaseAuthSource
import com.firestream.chat.data.remote.firebase.FirestoreUserSource
import com.firestream.chat.domain.model.User
import com.firestream.chat.domain.repository.UserRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.onEach
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class UserRepositoryImpl @Inject constructor(
    private val userDao: UserDao,
    private val userSource: FirestoreUserSource,
    private val authSource: FirebaseAuthSource
) : UserRepository {

    override fun observeUser(userId: String): Flow<User> {
        return userSource.observeUser(userId).onEach { user ->
            userDao.insertUser(UserEntity.fromDomain(user))
        }
    }

    override suspend fun getUserById(userId: String): Result<User> {
        return try {
            val cached = userDao.getUserById(userId)
            if (cached != null) {
                Result.success(cached.toDomain())
            } else {
                val remote = userSource.getUserById(userId)
                    ?: return Result.failure(Exception("User not found"))
                userDao.insertUser(UserEntity.fromDomain(remote))
                Result.success(remote)
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    override suspend fun updateProfile(displayName: String?, statusText: String?, avatarUrl: String?): Result<Unit> {
        return try {
            val uid = authSource.currentUserId ?: throw Exception("Not authenticated")
            val updates = mutableMapOf<String, Any?>()
            displayName?.let { updates["displayName"] = it }
            statusText?.let { updates["statusText"] = it }
            avatarUrl?.let { updates["avatarUrl"] = it }
            userSource.updateProfile(uid, updates)
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    override suspend fun setOnlineStatus(isOnline: Boolean): Result<Unit> {
        return try {
            val uid = authSource.currentUserId ?: throw Exception("Not authenticated")
            userSource.setOnlineStatus(uid, isOnline)
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    override suspend fun updateLastSeen(): Result<Unit> {
        return setOnlineStatus(true)
    }
}
