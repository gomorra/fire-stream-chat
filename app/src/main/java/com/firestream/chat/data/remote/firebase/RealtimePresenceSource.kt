package com.firestream.chat.data.remote.firebase

import com.google.firebase.database.FirebaseDatabase
import com.google.firebase.database.ServerValue
import kotlinx.coroutines.tasks.await
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Manages user presence via Firebase Realtime Database.
 *
 * RTDB has a server-side onDisconnect() mechanism that fires even when the device
 * abruptly disconnects (power off, crash, network loss). Firestore has no equivalent,
 * so the previous Firestore-only approach left users stuck "online" after powering off.
 *
 * Flow:
 *  - connect(): sets presence online + registers server-side onDisconnect to go offline
 *  - disconnect(): cancels onDisconnect + explicitly sets offline (for normal app close)
 *
 * A Cloud Function (syncPresenceToFirestore) mirrors RTDB presence changes → Firestore,
 * so the rest of the app (which observes Firestore) gets consistent updates.
 */
@Singleton
class RealtimePresenceSource @Inject constructor(
    private val database: FirebaseDatabase
) {
    suspend fun connect(userId: String) {
        val ref = database.getReference("presence/$userId")
        // Register server-side cleanup — runs even if device powers off abruptly
        ref.onDisconnect().setValue(
            mapOf("isOnline" to false, "lastSeen" to ServerValue.TIMESTAMP)
        ).await()
        ref.setValue(
            mapOf("isOnline" to true, "lastSeen" to ServerValue.TIMESTAMP)
        ).await()
    }

    suspend fun disconnect(userId: String) {
        val ref = database.getReference("presence/$userId")
        // Cancel the onDisconnect handler (no longer needed — we're going offline normally)
        ref.onDisconnect().cancel().await()
        ref.setValue(
            mapOf("isOnline" to false, "lastSeen" to ServerValue.TIMESTAMP)
        ).await()
    }
}
