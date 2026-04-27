// region: AGENT-NOTE
// PocketBase auth source — bridges Firebase Phone OTP to a PB session.
//
// Flow:
//   1. Firebase Phone OTP runs as in the firebase flavor (verifyOtp gives us
//      verificationId + otp).
//   2. signInWithVerification builds a PhoneAuthCredential, signs into Firebase
//      to get an ID token, POSTs /api/auth/firebase-bridge to swap it for a PB
//      session token, persists token+pbUserId via PocketBaseClient.setSession.
//   3. Firebase session is KEPT (no signOut) so we can refresh the ID token on
//      401.
//
// Don't put here:
//   - Direct password storage / username login — PB users are mint-only via
//     the bridge; pb_schema.json gates `users` with allowEmailAuth=false.
//   - Field-name mapping for non-auth paths — keep getUserDocument's
//     Firestore-shaped Map output isolated to the AuthRepository surface.
//   - Cached session-id state — `client.pbUserId` is the single source of
//     truth (StateFlow seeded once from DataStore at PocketBaseClient init).
// endregion
package com.firestream.chat.data.remote.pocketbase

import com.firestream.chat.data.remote.source.AuthSource
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.PhoneAuthProvider
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.tasks.await
import org.json.JSONObject
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class PocketBaseAuthSource @Inject constructor(
    private val client: PocketBaseClient,
    private val firebaseAuth: FirebaseAuth
) : AuthSource, PbTokenBridge {

    // The bridge endpoint returns the user's phone alongside the session
    // token; cache it for later [currentUserPhone] reads. Not in DataStore
    // because firebaseAuth.currentUser?.phoneNumber covers the cold-start
    // case before signInWithVerification runs.
    private val _currentPhone = MutableStateFlow<String?>(null)

    override val currentUserId: String?
        get() = client.pbUserId.value

    override val isLoggedIn: Boolean
        get() = client.pbUserId.value != null

    override val currentUserPhone: String?
        get() = _currentPhone.value ?: firebaseAuth.currentUser?.phoneNumber

    override suspend fun signInWithVerification(verificationId: String, otp: String): String {
        val credential = PhoneAuthProvider.getCredential(verificationId, otp)
        val firebaseUser = firebaseAuth.signInWithCredential(credential).await().user
            ?: throw RuntimeException("Firebase sign-in returned no user")
        val idToken = firebaseUser.getIdToken(false).await().token
            ?: throw RuntimeException("Firebase getIdToken returned null")

        val response = bridgeIdToken(idToken)
        return applyBridgeResponse(response, fallbackPhone = firebaseUser.phoneNumber)
    }

    override suspend fun createUserDocument(
        uid: String,
        phoneNumber: String,
        displayName: String,
        avatarUrl: String?
    ) {
        // The bridge already created the record on first sign-in, so this is an
        // update (PATCH) that fills in the profile-setup fields. PB's auth
        // collection allows the owner (`id = @request.auth.id`) to mutate.
        val body = JSONObject().apply {
            put("phone", phoneNumber)
            put("name", displayName)
            put("avatar_url", avatarUrl ?: "")
            put("status_text", "Hey there! I'm using FireStream")
        }
        client.patch("/api/collections/users/records/$uid", body)
        _currentPhone.value = phoneNumber
    }

    override suspend fun getUserDocument(uid: String): Map<String, Any>? {
        val record = runCatching {
            client.get("/api/collections/users/records/$uid")
        }.getOrNull() ?: return null
        // Treat empty `name` as "profile not set up yet" — matches the firebase
        // semantics where the firestore doc doesn't exist until createUserProfile
        // runs. AuthRepositoryImpl uses null to route the user to profile setup.
        val name = record.optString("name")
        if (name.isEmpty()) return null
        return mapOf(
            "uid" to uid,
            "phoneNumber" to record.optString("phone"),
            "displayName" to name,
            "avatarUrl" to record.optString("avatar_url"),
            "statusText" to record.optString("status_text"),
            "lastSeen" to System.currentTimeMillis(),
            "isOnline" to true
        )
    }

    override suspend fun updateFcmToken(uid: String, token: String) {
        val body = JSONObject().put("fcm_token", token)
        runCatching { client.patch("/api/collections/users/records/$uid", body) }
    }

    override fun signOut() {
        client.clearSession()
        firebaseAuth.signOut()
        _currentPhone.value = null
    }

    override suspend fun refreshSession(): Boolean {
        val firebaseUser = firebaseAuth.currentUser ?: return false
        val idToken = runCatching {
            firebaseUser.getIdToken(true).await().token
        }.getOrNull() ?: return false
        val response = runCatching { bridgeIdToken(idToken) }.getOrNull() ?: return false
        applyBridgeResponse(response, fallbackPhone = firebaseUser.phoneNumber)
        return true
    }

    private suspend fun bridgeIdToken(idToken: String): JSONObject {
        val body = JSONObject().put("idToken", idToken)
        return client.unauthedPost("/api/auth/firebase-bridge", body)
    }

    private fun applyBridgeResponse(response: JSONObject, fallbackPhone: String?): String {
        val pbToken = response.getString("token")
        val record = response.getJSONObject("record")
        val pbUserId = record.getString("id")
        val phone = record.optString("phone").takeIf { it.isNotEmpty() }
        client.setSession(pbToken, pbUserId)
        _currentPhone.value = phone ?: fallbackPhone
        return pbUserId
    }
}
