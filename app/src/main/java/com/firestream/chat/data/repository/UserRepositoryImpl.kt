package com.firestream.chat.data.repository

import android.net.Uri
import com.firestream.chat.data.local.dao.UserDao
import com.firestream.chat.data.local.entity.UserEntity
import com.firestream.chat.data.remote.firebase.FirebaseAuthSource
import com.firestream.chat.data.remote.firebase.FirebaseStorageSource
import com.firestream.chat.data.remote.firebase.FirestoreUserSource
import com.firestream.chat.data.remote.firebase.RealtimePresenceSource
import com.firestream.chat.data.util.ProfileImageManager
import com.firestream.chat.domain.model.User
import com.firestream.chat.domain.repository.UserRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class UserRepositoryImpl @Inject constructor(
    private val userDao: UserDao,
    private val userSource: FirestoreUserSource,
    private val authSource: FirebaseAuthSource,
    private val storageSource: FirebaseStorageSource,
    private val presenceSource: RealtimePresenceSource,
    private val profileImageManager: ProfileImageManager
) : UserRepository {

    override fun observeUser(userId: String): Flow<User> {
        return combine(
            userSource.observeUser(userId),
            presenceSource.observeOnlineStatus(userId)
        ) { user, isOnline ->
            user.copy(isOnline = isOnline)
        }.onEach { user ->
            val existing = userDao.getUserById(user.uid)
            // Preserve avatar cache fields during Firestore sync
            val entity = UserEntity.fromDomain(user).let {
                if (existing != null) {
                    it.copy(
                        cachedAvatarUrl = existing.cachedAvatarUrl,
                        localAvatarPath = existing.localAvatarPath
                    )
                } else it
            }
            userDao.insertUser(entity)

            // Download avatar if URL changed, local file is missing, or a previous download failed.
            if (user.avatarUrl != null) {
                val needsDownload = user.avatarUrl != existing?.cachedAvatarUrl ||
                    existing?.localAvatarPath == null ||
                    !profileImageManager.fileExists(user.uid)
                if (needsDownload) {
                    try {
                        val file = profileImageManager.downloadAvatar(user.uid, user.avatarUrl)
                        userDao.updateAvatarCache(user.uid, user.avatarUrl, file.absolutePath)
                    } catch (_: Exception) { /* Will retry on next emission */ }
                }
            } else if (existing?.localAvatarPath != null) {
                // Avatar was removed
                profileImageManager.deleteAvatar(user.uid)
                userDao.updateAvatarCache(user.uid, null, null)
            }
        }.map { user ->
            // Re-read from Room to get localAvatarPath
            val entity = userDao.getUserById(user.uid)
            user.copy(localAvatarPath = entity?.localAvatarPath)
        }
    }

    override suspend fun getUserById(userId: String): Result<User> {
        return try {
            val cached = userDao.getUserById(userId)
            if (cached != null) {
                val domain = cached.toDomain()
                // Verify local file still exists
                val localPath = if (cached.localAvatarPath != null && profileImageManager.fileExists(userId)) {
                    cached.localAvatarPath
                } else null
                Result.success(domain.copy(localAvatarPath = localPath))
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

    override suspend fun uploadAvatar(uri: Uri): Result<String> {
        return try {
            val uid = authSource.currentUserId ?: throw Exception("Not authenticated")
            // Save local copy before uploading to avoid re-download
            val localFile = profileImageManager.saveLocalCopy(uid, uri)
            val url = storageSource.uploadAvatar(uid, uri)
            userSource.updateProfile(uid, mapOf("avatarUrl" to url))
            // Update cache immediately
            userDao.updateAvatarCache(uid, url, localFile.absolutePath)
            Result.success(url)
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
            // RTDB handles the abrupt-disconnect case via onDisconnect() — runs server-side
            // even if the device powers off before onStop() can fire. The Cloud Function
            // syncPresenceToFirestore is the sole writer to Firestore (avoids race with direct writes).
            if (isOnline) presenceSource.startPresence(uid) else presenceSource.goOffline(uid)
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    override suspend fun updateLastSeen(): Result<Unit> {
        return setOnlineStatus(true)
    }

    override suspend fun blockUser(userId: String): Result<Unit> {
        return try {
            val uid = authSource.currentUserId ?: throw Exception("Not authenticated")
            userSource.blockUser(uid, userId)
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    override suspend fun unblockUser(userId: String): Result<Unit> {
        return try {
            val uid = authSource.currentUserId ?: throw Exception("Not authenticated")
            userSource.unblockUser(uid, userId)
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    override suspend fun isUserBlocked(userId: String): Boolean {
        val uid = authSource.currentUserId ?: return false
        return try {
            userSource.isUserBlocked(uid, userId)
        } catch (_: Exception) {
            false
        }
    }

    override suspend fun updateReadReceipts(enabled: Boolean): Result<Unit> {
        return try {
            val uid = authSource.currentUserId ?: throw Exception("Not authenticated")
            userSource.updateProfile(uid, mapOf("readReceiptsEnabled" to enabled))
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
}
