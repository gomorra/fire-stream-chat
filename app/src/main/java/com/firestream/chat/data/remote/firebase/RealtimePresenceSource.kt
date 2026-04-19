package com.firestream.chat.data.remote.firebase

import android.util.Log
import com.google.firebase.database.DataSnapshot
import com.google.firebase.database.DatabaseError
import com.google.firebase.database.FirebaseDatabase
import com.google.firebase.database.ServerValue
import com.google.firebase.database.ValueEventListener
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.onStart
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
    @Synchronized
    fun startPresence(userId: String) {
        Log.d(TAG, "startPresence called for userId=$userId (current=$currentUserId, hasListener=${connectedListener != null})")
        if (currentUserId == userId && connectedListener != null) {
            // Listener already registered — but we may have been set offline by goOffline()
            // (e.g. app switcher round-trip). Force-write online status since .info/connected
            // won't re-fire when the RTDB connection was never lost.
            Log.d(TAG, "startPresence: listener exists, force-writing online status")
            val presenceRef = database.getReference("presence/$userId")
            val onlineData = mapOf("isOnline" to true, "lastSeen" to ServerValue.TIMESTAMP)
            presenceRef.setValue(onlineData)
                .addOnSuccessListener { Log.d(TAG, "force setValue(online) OK for $userId") }
                .addOnFailureListener { Log.e(TAG, "force setValue(online) FAILED for $userId", it) }
            return
        }

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
                Log.d(TAG, ".info/connected = $connected")
                if (!connected) return

                presenceRef.onDisconnect().setValue(offlineData)
                    .addOnSuccessListener { Log.d(TAG, "onDisconnect registered OK") }
                    .addOnFailureListener { Log.e(TAG, "onDisconnect FAILED", it) }
                presenceRef.setValue(onlineData)
                    .addOnSuccessListener { Log.d(TAG, "setValue(online) OK for $userId") }
                    .addOnFailureListener { Log.e(TAG, "setValue(online) FAILED for $userId", it) }
            }

            override fun onCancelled(error: DatabaseError) {
                Log.e(TAG, ".info/connected listener cancelled: ${error.message}")
            }
        }

        connectedRef.addValueEventListener(listener)
        connectedListener = listener
    }

    /**
     * Writes offline status to RTDB and tears down the `.info/connected` listener.
     *
     * Removing the listener is essential: otherwise, after the user has
     * intentionally gone offline, a later RTDB socket reconnect (e.g. triggered
     * when the device wakes to process an incoming FCM push) would fire
     * `.info/connected = true` again and the handler would write
     * `isOnline: true` — making the user briefly appear online to observers.
     *
     * Does NOT cancel the server-side `onDisconnect()` registration — worst case
     * is a redundant offline write from the server, which is harmless. This
     * avoids the race where the process dies between cancel() and setValue().
     */
    suspend fun goOffline(userId: String) {
        Log.d(TAG, "goOffline called for userId=$userId")
        synchronized(this) {
            connectedListener?.let { listener ->
                database.getReference(".info/connected").removeEventListener(listener)
            }
            connectedListener = null
            currentUserId = null
        }
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
        Log.d(TAG, "observeOnlineStatus: listening on presence/$userId")
        val presenceRef = database.getReference("presence/$userId")
        val listener = object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) {
                val isOnline = snapshot.child("isOnline").getValue(Boolean::class.java) ?: false
                Log.d(TAG, "observeOnlineStatus($userId): isOnline=$isOnline, snapshot=${snapshot.value}")
                trySend(isOnline)
            }

            override fun onCancelled(error: DatabaseError) {
                Log.e(TAG, "observeOnlineStatus($userId) cancelled: ${error.message}")
                trySend(false)
            }
        }
        presenceRef.addValueEventListener(listener)
        awaitClose { presenceRef.removeEventListener(listener) }
    }.onStart { emit(false) }

    /**
     * Removes the `.info/connected` listener. Called on logout.
     */
    @Synchronized
    fun stopPresence() {
        Log.d(TAG, "stopPresence called (currentUserId=$currentUserId)")
        connectedListener?.let { listener ->
            database.getReference(".info/connected").removeEventListener(listener)
        }
        connectedListener = null
        currentUserId = null
    }

    companion object {
        private const val TAG = "Presence"
    }
}
