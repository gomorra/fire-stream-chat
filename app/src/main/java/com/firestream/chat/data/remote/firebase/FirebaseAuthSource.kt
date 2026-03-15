package com.firestream.chat.data.remote.firebase

import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.PhoneAuthCredential
import com.google.firebase.auth.PhoneAuthOptions
import com.google.firebase.auth.PhoneAuthProvider
import com.google.firebase.firestore.FirebaseFirestore
import kotlinx.coroutines.tasks.await
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class FirebaseAuthSource @Inject constructor(
    private val auth: FirebaseAuth,
    private val firestore: FirebaseFirestore
) {
    val currentUserId: String?
        get() = auth.currentUser?.uid

    val isLoggedIn: Boolean
        get() = auth.currentUser != null

    val currentUserPhone: String?
        get() = auth.currentUser?.phoneNumber

    fun getPhoneAuthOptions(
        phoneNumber: String,
        callbacks: PhoneAuthProvider.OnVerificationStateChangedCallbacks
    ): PhoneAuthOptions.Builder {
        return PhoneAuthOptions.newBuilder(auth)
            .setPhoneNumber(phoneNumber)
            .setTimeout(60L, java.util.concurrent.TimeUnit.SECONDS)
            .setCallbacks(callbacks)
    }

    suspend fun signInWithCredential(credential: PhoneAuthCredential): String {
        val result = auth.signInWithCredential(credential).await()
        return result.user?.uid ?: throw Exception("Sign in failed: no user returned")
    }

    suspend fun createUserDocument(
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

    suspend fun getUserDocument(uid: String): Map<String, Any>? {
        val doc = firestore.collection("users").document(uid).get().await()
        return if (doc.exists()) doc.data else null
    }

    suspend fun updateFcmToken(uid: String, token: String) {
        firestore.collection("users").document(uid)
            .update("fcmToken", token)
            .await()
    }

    fun signOut() {
        auth.signOut()
    }
}
