// region: AGENT-NOTE
// PocketBase user source — REST GET/PATCH on `users` records + SSE record
// subscription for [observeUser]. Field-name remap (PB ↔ domain) lives in
// [PbUserMapper]; keep `users`-specific mapping isolated there so the
// AuthRepository's Firestore-shaped getUserDocument map stays consistent.
//
// Don't put here:
//   - Block-list ops (out of v0 scope; stay no-op stubs).
//   - Online-status writes (PocketBasePresenceSource owns the heartbeat path;
//     setOnlineStatus stays a no-op so the Firebase-flavor Cloud Function
//     replacement doesn't double-write).
// endregion
package com.firestream.chat.data.remote.pocketbase

import com.firestream.chat.data.remote.source.UserSource
import com.firestream.chat.domain.model.User
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.mapNotNull
import kotlinx.coroutines.flow.onStart
import org.json.JSONObject
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class PocketBaseUserSource @Inject constructor(
    private val client: PocketBaseClient,
    private val realtime: PocketBaseRealtime
) : UserSource {

    /**
     * Live user document. Emits the current snapshot once (best-effort GET) and
     * then every PB realtime `update`/`create` event for `users/{userId}`. SSE
     * `delete` events are dropped — the upstream code treats absent users as
     * "loaded null", which `observeUser` must not produce mid-stream.
     */
    override fun observeUser(userId: String): Flow<User> {
        val topic = "users/$userId"
        val updates = realtime.subscribe(topic)
            .mapNotNull { event ->
                if (event !is RealtimeEvent.Record) return@mapNotNull null
                if (event.action == "delete") return@mapNotNull null
                event.data.optJSONObject("record")?.let(PbUserMapper::fromRecord)
            }
        return updates.onStart {
            runCatching { getUserById(userId) }.getOrNull()?.let { emit(it) }
        }
    }

    override suspend fun getUserById(userId: String): User? {
        if (userId.isEmpty()) return null
        val record = runCatching {
            client.get("/api/collections/users/records/$userId")
        }.getOrNull() ?: return null
        return PbUserMapper.fromRecord(record)
    }

    override suspend fun updateProfile(userId: String, updates: Map<String, Any?>) {
        val pbUpdates = PbUserMapper.toPbUpdates(updates).filterValues { it != null }
        if (pbUpdates.isEmpty()) return
        val body = JSONObject(pbUpdates)
        client.patch("/api/collections/users/records/$userId", body)
    }

    /** No-op: presence lives in the `presence` collection — see PocketBasePresenceSource. */
    override suspend fun setOnlineStatus(userId: String, isOnline: Boolean) {
        // Intentionally empty.
    }

    // ── Block list — out of scope for v0 ───────────────────────────────────────

    override suspend fun blockUser(currentUserId: String, targetUserId: String) {
        // No-op: block list is a follow-up plan.
    }

    override suspend fun unblockUser(currentUserId: String, targetUserId: String) {
        // No-op: block list is a follow-up plan.
    }

    override suspend fun isUserBlocked(currentUserId: String, targetUserId: String): Boolean = false

    override suspend fun getBlockedUserIds(userId: String): Set<String> = emptySet()
}
