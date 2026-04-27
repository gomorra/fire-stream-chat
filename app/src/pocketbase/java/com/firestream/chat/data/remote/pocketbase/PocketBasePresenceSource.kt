package com.firestream.chat.data.remote.pocketbase

import com.firestream.chat.data.remote.source.PresenceSource
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Step 4 stub. Real impl in step 6 (heartbeat coroutine + SSE on
 * `presence/{user_id}` + server-side cron sweeper).
 */
@Singleton
class PocketBasePresenceSource @Inject constructor() : PresenceSource {
    override fun startPresence(userId: String) {
        // No-op stub.
    }

    override suspend fun goOffline(userId: String) {
        // No-op stub.
    }

    override fun observeOnlineStatus(userId: String): Flow<Boolean> = flowOf(false)

    override fun stopPresence() {
        // No-op stub.
    }
}
