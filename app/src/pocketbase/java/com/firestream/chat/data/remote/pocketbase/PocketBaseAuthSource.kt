package com.firestream.chat.data.remote.pocketbase

import com.firestream.chat.data.remote.source.AuthSource
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Step 4 stub. Real impl lands in step 5 (Firebase ID token → PB session
 * bridge). Reading `currentUserId` while logged-out returns null, matching
 * the firebase impl's pre-login behaviour, so DI graph initialization doesn't
 * blow up.
 */
@Singleton
class PocketBaseAuthSource @Inject constructor() : AuthSource {
    override val currentUserId: String? = null
    override val isLoggedIn: Boolean = false
    override val currentUserPhone: String? = null

    override suspend fun signInWithVerification(verificationId: String, otp: String): String =
        throw NotImplementedError("PB v0 stub")

    override suspend fun createUserDocument(
        uid: String,
        phoneNumber: String,
        displayName: String,
        avatarUrl: String?
    ): Unit = throw NotImplementedError("PB v0 stub")

    override suspend fun getUserDocument(uid: String): Map<String, Any>? =
        throw NotImplementedError("PB v0 stub")

    override suspend fun updateFcmToken(uid: String, token: String): Unit =
        throw NotImplementedError("PB v0 stub")

    override fun signOut() {
        // No-op stub — overridden in step 5.
    }
}
