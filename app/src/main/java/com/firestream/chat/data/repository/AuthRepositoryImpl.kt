// region: AGENT-NOTE
// Responsibility: Phone-OTP authentication, FCM token registration, sign-out.
// Owns: Current backend user state. Sign-out clears AppDatabase + SignalDatabase
//   so a fresh signed-in user gets clean local state and fresh Signal keys.
// Collaborators: AuthSource (interface — Firebase impl in firebase/, PocketBase
//   impl bridges Firebase ID token → PB session), FirebaseMessaging (FCM tokens —
//   both flavors keep FCM device-side), AppDatabase + SignalDatabase (clear on
//   sign-out), SignalManager (re-init on sign-in), UserDao (cache the new user).
// Don't put here: OTP send (FirebasePhoneAuth helper consumed by AuthViewModel),
//   profile-edit operations (UserRepositoryImpl), session presence
//   (PresenceSource), key-bundle exchange (KeySource).
// endregion

package com.firestream.chat.data.repository

import android.util.Log
import com.firestream.chat.data.crypto.SignalManager
import com.firestream.chat.data.local.AppDatabase
import com.firestream.chat.data.local.SignalDatabase
import com.firestream.chat.data.local.dao.UserDao
import com.firestream.chat.data.local.entity.UserEntity
import com.firestream.chat.data.remote.source.AuthSource
import com.firestream.chat.domain.model.User
import com.firestream.chat.domain.repository.AuthRepository
import com.google.firebase.messaging.FirebaseMessaging
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class AuthRepositoryImpl @Inject constructor(
    private val authSource: AuthSource,
    private val database: AppDatabase,
    private val signalDatabase: SignalDatabase,
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
            val uid = authSource.signInWithVerification(verificationId, otp)
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
            
            // Sync FCM token for the new profile
            try {
                val token = firebaseMessaging.token.await()
                authSource.updateFcmToken(uid, token)
            } catch (_: Exception) { }

            Result.success(user)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    override suspend fun getCurrentUser(): Result<User?> {
        return try {
            val uid = currentUserId ?: return Result.success(null)

            // Sync FCM token in the background — must not block the auth check.
            // After Doze/power-saving, firebaseMessaging.token can hang for 10–30 s.
            CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
                try {
                    val token = firebaseMessaging.token.await()
                    authSource.updateFcmToken(uid, token)
                    Log.d("AuthRepository", "FCM token synced successfully")
                } catch (e: Exception) {
                    Log.e("AuthRepository", "FCM token sync failed", e)
                }
            }

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

    override suspend fun signOut() {
        database.clearAllTables()
        signalDatabase.clearAllTables()
        authSource.signOut()
    }
}
