package com.firestream.chat.data.remote.firebase

import com.google.firebase.database.DataSnapshot
import com.google.firebase.database.DatabaseError
import com.google.firebase.database.FirebaseDatabase
import com.google.firebase.database.ServerValue
import com.google.firebase.database.ValueEventListener
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.tasks.await
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Manages user presence via Firebase Realtime Database.
 *
 * Uses the standard Firebase presence pattern: a `.info/connected` listener
 * re-registers onDisconnect() on every reconnect, so the server-side cleanup
 * handler is never lost — even after network changes or RTDB timeouts.
 *
 * A Cloud Function (syncPresenceToFirestore) mirrors RTDB presence changes
 * to Firestore, so the rest of the app observes consistent updates.
 */
@Singleton
class RealtimePresenceSource @Inject constructor(
    private val database: FirebaseDatabase
) {
    private var connectedListener: ValueEventListener? = null
    private var currentUserId: String? = null

    /**
     * Starts presence monitoring for [userId].
     *
     * Registers a `.info/connected` listener that, on every connect/reconnect:
     * 1. Registers an onDisconnect() handler to write offline status
     * 2. Writes online status
     *
     * Idempotent for the same userId. For a different userId, removes the old
     * listener before registering a new one.
     */
    fun startPresence(userId: String) {
        if (currentUserId == userId && connectedListener != null) return

        // Clean up any existing listener for a different user
        if (connectedListener != null) {
            stopPresence()
        }

        currentUserId = userId
        val presenceRef = database.getReference("presence/$userId")
        val connectedRef = database.getReference(".info/connected")
        val offlineData = mapOf("isOnline" to false, "lastSeen" to ServerValue.TIMESTAMP)
        val onlineData = mapOf("isOnline" to true, "lastSeen" to ServerValue.TIMESTAMP)

        val listener = object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) {
                val connected = snapshot.getValue(Boolean::class.java) ?: false
                if (!connected) return

                // Re-register onDisconnect on every reconnect, then go online
                presenceRef.onDisconnect().setValue(offlineData)
                    .addOnSuccessListener {
                        presenceRef.setValue(onlineData)
                    }
            }

            override fun onCancelled(error: DatabaseError) {
                // No-op — listener will retry on next connection event
            }
        }

        connectedRef.addValueEventListener(listener)
        connectedListener = listener
    }

    /**
     * Writes offline status to RTDB. Does NOT cancel onDisconnect() — worst case
     * is a redundant offline write from the server, which is harmless. This avoids
     * the race where the process dies between cancel() and setValue().
     */
    suspend fun goOffline(userId: String) {
        val presenceRef = database.getReference("presence/$userId")
        presenceRef.setValue(
            mapOf("isOnline" to false, "lastSeen" to ServerValue.TIMESTAMP)
        ).await()
    }

    /**
     * Observes the live online status of [userId] directly from RTDB.
     * Does not depend on the Cloud Function sync — changes are visible instantly.
     */
    fun observeOnlineStatus(userId: String): Flow<Boolean> = callbackFlow {
        val presenceRef = database.getReference("presence/$userId")
        val listener = object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) {
                val isOnline = snapshot.child("isOnline").getValue(Boolean::class.java) ?: false
                trySend(isOnline)
            }

            override fun onCancelled(error: DatabaseError) {
                trySend(false)
            }
        }
        presenceRef.addValueEventListener(listener)
        awaitClose { presenceRef.removeEventListener(listener) }
    }

    /**
     * Removes the `.info/connected` listener. Called on logout.
     */
    fun stopPresence() {
        connectedListener?.let { listener ->
            database.getReference(".info/connected").removeEventListener(listener)
        }
        connectedListener = null
        currentUserId = null
    }
}
