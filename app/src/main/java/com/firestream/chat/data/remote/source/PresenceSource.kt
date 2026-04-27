package com.firestream.chat.data.remote.source

import kotlinx.coroutines.flow.Flow

/**
 * Backend-neutral online-presence boundary.
 *
 * Firebase impl uses RTDB `.info/connected` + `onDisconnect()` to handle abrupt
 * termination server-side. PocketBase impl runs a heartbeat coroutine inside the
 * source class plus a server-side cron sweeper (no `onDisconnect` equivalent on
 * PB; abrupt termination shows online for ~60 s).
 */
interface PresenceSource {
    /** Idempotent for the same userId. Starts whatever liveness mechanism the impl uses. */
    fun startPresence(userId: String)

    /** Best-effort offline write + tear down liveness mechanism. */
    suspend fun goOffline(userId: String)

    fun observeOnlineStatus(userId: String): Flow<Boolean>

    /** Called on logout. */
    fun stopPresence()
}
