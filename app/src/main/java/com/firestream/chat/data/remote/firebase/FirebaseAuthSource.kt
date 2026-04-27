package com.firestream.chat.data.remote.firebase

import com.firestream.chat.data.remote.source.AuthSource
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.PhoneAuthProvider
import com.google.firebase.firestore.FirebaseFirestore
import kotlinx.coroutines.tasks.await
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class FirebaseAuthSource @Inject constructor(
    private val auth: FirebaseAuth,
    private val firestore: FirebaseFirestore
) : AuthSource {

    override val currentUserId: String?
        get() = auth.currentUser?.uid

    override val isLoggedIn: Boolean
        get() = auth.currentUser != null

    override val currentUserPhone: String?
        get() = auth.currentUser?.phoneNumber

    override suspend fun signInWithVerification(verificationId: String, otp: String): String {
        val credential = PhoneAuthProvider.getCredential(verificationId, otp)
        val result = auth.signInWithCredential(credential).await()
        return result.user?.uid ?: throw Exception("Sign in failed: no user returned")
    }

    override suspend fun createUserDocument(
        uid: String,
        phoneNumber: String,
        displayName: String,
        avatarUrl: String?
    ) {
        val userData = hashMapOf(
            "uid" to uid,
            "phoneNumber" to phoneNumber,
            "displayName" to displayName,
            "avatarUrl" to avatarUrl,
            "statusText" to "Hey there! I'm using FireStream",
            "lastSeen" to System.currentTimeMillis(),
            "isOnline" to true,
            "createdAt" to System.currentTimeMillis()
        )
        firestore.collection("users").document(uid).set(userData).await()
    }

    override suspend fun getUserDocument(uid: String): Map<String, Any>? {
        val doc = firestore.collection("users").document(uid).get().await()
        return if (doc.exists()) doc.data else null
    }

    override suspend fun updateFcmToken(uid: String, token: String) {
        firestore.collection("users").document(uid)
            .update("fcmToken", token)
            .await()
    }

    override fun signOut() {
        auth.signOut()
    }
}
