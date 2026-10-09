package com.firestream.chat.domain.repository

import com.firestream.chat.domain.model.User

interface AuthRepository {
    val currentUserId: String?
    val isLoggedIn: Boolean

    suspend fun verifyOtp(verificationId: String, otp: String): Result<User>
    suspend fun createUserProfile(displayName: String, avatarUrl: String?): Result<User>
    suspend fun getCurrentUser(): Result<User?>
    suspend fun updateFcmToken(token: String): Result<Unit>

    /**
     * Say on the signed-in user's document that this app takes a video line in a call. A caller
     * offers one only to a user who said so. Does nothing while signed out.
     */
    suspend fun announceCallVideoLine(): Result<Unit>
    suspend fun signOut()
}
