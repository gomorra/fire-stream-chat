// region: AGENT-NOTE
// Responsibility: User presence on Firebase RTDB at `/presence/{uid}`.
//   `.info/connected` pattern + `onDisconnect()` registration handles abrupt
//   disconnects without client cooperation. Presence transitions documented
//   in the KDoc just below.
// Owns: RTDB writes to /presence/* + idempotency state for foreground reentry.
// Collaborators: AppLifecycleObserver (process-level driver — startPresence on
//   onStart, goOffline on onStop), UserRepositoryImpl (consumer of
//   observeOnlineStatus), Cloud Function syncPresenceToFirestore (mirrors to
//   Firestore for abrupt-termination cases).
// Don't put here: lifecycle observer logic (AppLifecycleObserver), Firestore
//   mirror (Cloud Function), cached presence in users table (UserRepositoryImpl).
// endregion

package com.firestream.chat.data.remote.firebase

import android.util.Log
import com.firestream.chat.data.remote.source.PresenceSource
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
 * Manages user online presence via Firebase Realtime Database (RTDB).
 *
 * ## RTDB schema
 * `/presence/{uid}` → `{ isOnline: Boolean, lastSeen: ServerValue.TIMESTAMP }`
 *
 * ## State transitions
 * 1. **Foreground enter** — `AppLifecycleObserver.onStart` → [startPresence]
 *    registers a `.info/connected` listener. On every (re)connect it registers
 *    `onDisconnect().setValue(offline)` then writes `isOnline=true`.
 * 2. **Background leave** — `AppLifecycleObserver.onStop` → [goOffline] tears
 *    down the `.info/connected` listener (so a later socket reconnect cannot
 *    flip the user back to online) and writes `isOnline=false`.
 * 3. **Abrupt termination** (crash, force-quit, radio loss) — the server-side
 *    `onDisconnect` registered in step 1 fires on the RTDB server and writes
 *    `isOnline=false` without any client involvement.
 * 4. **Reconnect while already tracking** (app-switcher round trip where the
 *    RTDB socket never dropped) — idempotent [startPresence] call force-writes
 *    `isOnline=true` because `.info/connected` won't re-fire.
 * 5. **Logout** — not currently handled here; signed-out users stay online in
 *    RTDB until the app is backgrounded. Tracked as a separate defect.
 *
 * ## Invariant
 * At most one `.info/connected` listener per process at any time. Violating
 * this double-writes presence on every reconnect.
 *
 * ## Read side
 * [observeOnlineStatus] reads `/presence/{uid}/isOnline` directly; callers
 * should not read `User.isOnline` from Firestore. `UserRepositoryImpl.observeUser`
 * combines the two sources and lets RTDB win.
 *
 * The Cloud Function `syncPresenceToFirestore` mirrors RTDB presence changes
 * to Firestore as a fallback for clients that don't subscribe to RTDB directly.
 */
@Singleton
class RealtimePresenceSource @Inject constructor(
    private val database: FirebaseDatabase
) : PresenceSource {
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
    override fun startPresence(userId: String) {
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
    override suspend fun goOffline(userId: String) {
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
    override fun observeOnlineStatus(userId: String): Flow<Boolean> = callbackFlow {
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
    override fun stopPresence() {
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
