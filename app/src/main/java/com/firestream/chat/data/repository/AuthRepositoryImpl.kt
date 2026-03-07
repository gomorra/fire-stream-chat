package com.firestream.chat.data.repository

import com.firestream.chat.data.crypto.SignalManager
import com.firestream.chat.data.local.dao.UserDao
import com.firestream.chat.data.local.entity.UserEntity
import com.firestream.chat.data.remote.firebase.FirebaseAuthSource
import com.firestream.chat.domain.model.User
import com.firestream.chat.domain.repository.AuthRepository
import com.google.firebase.auth.PhoneAuthCredential
import com.google.firebase.auth.PhoneAuthProvider
import com.google.firebase.messaging.FirebaseMessaging
import kotlinx.coroutines.tasks.await
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class AuthRepositoryImpl @Inject constructor(
    private val authSource: FirebaseAuthSource,
    private val userDao: UserDao,
    private val signalManager: SignalManager,
    private val firebaseMessaging: FirebaseMessaging
) : AuthRepository {

    override val currentUserId: String?
        get() = authSource.currentUserId

    override val isLoggedIn: Boolean
        get() = authSource.isLoggedIn

    override suspend fun verifyOtp(verificationId: String, otp: String): Result<User> {
        return try {
            val credential = PhoneAuthProvider.getCredential(verificationId, otp)
            val uid = authSource.signInWithCredential(credential)
            // Sync FCM token now that we have a UID — onNewToken may have fired before login
            try {
                val token = firebaseMessaging.token.await()
                authSource.updateFcmToken(uid, token)
            } catch (_: Exception) { }
            val userData = authSource.getUserDocument(uid)
            if (userData != null) {
                val user = User(
                    uid = uid,
                    phoneNumber = userData["phoneNumber"] as? String ?: "",
                    displayName = userData["displayName"] as? String ?: "",
                    avatarUrl = userData["avatarUrl"] as? String,
                    statusText = userData["statusText"] as? String ?: "",
                    lastSeen = userData["lastSeen"] as? Long ?: 0L,
                    isOnline = true
                )
                userDao.insertUser(UserEntity.fromDomain(user))
                signalManager.ensureInitialized()
                Result.success(user)
            } else {
                // New user — needs profile setup; keys will be initialised in createUserProfile
                Result.success(User(uid = uid))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    override suspend fun createUserProfile(displayName: String, avatarUrl: String?): Result<User> {
        return try {
            val uid = currentUserId ?: throw Exception("Not authenticated")
            val phoneNumber = authSource.currentUserPhone ?: ""
            authSource.createUserDocument(uid, phoneNumber, displayName, avatarUrl)
            signalManager.ensureInitialized()

            val user = User(
                uid = uid,
                phoneNumber = phoneNumber,
                displayName = displayName,
                avatarUrl = avatarUrl,
                isOnline = true
            )
            userDao.insertUser(UserEntity.fromDomain(user))
            Result.success(user)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    override suspend fun getCurrentUser(): Result<User?> {
        return try {
            val uid = currentUserId ?: return Result.success(null)
            val cached = userDao.getUserById(uid)
            if (cached != null) {
                Result.success(cached.toDomain())
            } else {
                val remote = authSource.getUserDocument(uid)
                if (remote != null) {
                    val user = User(
                        uid = uid,
                        phoneNumber = remote["phoneNumber"] as? String ?: "",
                        displayName = remote["displayName"] as? String ?: "",
                        avatarUrl = remote["avatarUrl"] as? String,
                        statusText = remote["statusText"] as? String ?: "",
                        lastSeen = remote["lastSeen"] as? Long ?: 0L,
                        isOnline = true
                    )
                    userDao.insertUser(UserEntity.fromDomain(user))
                    Result.success(user)
                } else {
                    Result.success(null)
                }
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    override suspend fun updateFcmToken(token: String): Result<Unit> {
        return try {
            val uid = currentUserId ?: throw Exception("Not authenticated")
            authSource.updateFcmToken(uid, token)
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    override fun signOut() {
        authSource.signOut()
    }
}
