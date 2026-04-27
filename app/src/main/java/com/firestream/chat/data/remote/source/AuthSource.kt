package com.firestream.chat.data.remote.source

/**
 * Backend-neutral authentication boundary.
 *
 * The Firebase impl drives Phone OTP directly. The PocketBase impl runs the
 * same Phone OTP up front (via the shared [FirebasePhoneAuth] helper) and then
 * exchanges the resulting Firebase ID token for a PB session token via the
 * `firebase_bridge` JS hook — so [signInWithVerification] returns the local
 * `currentUserId` (Firebase UID for the firebase impl, PB record id for the
 * pocketbase impl) for either backend.
 */
interface AuthSource {
    val currentUserId: String?
    val isLoggedIn: Boolean
    val currentUserPhone: String?

    /**
     * Completes Phone OTP. The verification id and otp come from the
     * upstream Firebase Phone OTP flow (driven through [FirebasePhoneAuth]).
     * Returns the local user id (Firebase UID or PB record id depending on
     * which backend the impl talks to).
     */
    suspend fun signInWithVerification(verificationId: String, otp: String): String

    suspend fun createUserDocument(
        uid: String,
        phoneNumber: String,
        displayName: String,
        avatarUrl: String?
    )

    suspend fun getUserDocument(uid: String): Map<String, Any>?

    suspend fun updateFcmToken(uid: String, token: String)

    fun signOut()
}
