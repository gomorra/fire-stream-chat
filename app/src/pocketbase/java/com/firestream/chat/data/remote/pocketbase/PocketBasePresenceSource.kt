// region: AGENT-NOTE
// PocketBase presence source — heartbeat-based liveness, since PB has no
// `onDisconnect()` equivalent.
//
// State machine:
//   * startPresence(uid)            : ensures presence record exists, launches
//                                     the heartbeat job (PATCH last_heartbeat
//                                     every [HEARTBEAT_INTERVAL_MS]). Idempotent
//                                     for the same uid; for a new uid the old
//                                     job is cancelled first.
//   * goOffline(uid)                : cancels the heartbeat job, PATCHes
//                                     is_online=false. Best-effort.
//   * observeOnlineStatus(uid)      : SSE on `presence/{uid}` record + an
//                                     initial REST GET; emits is_online &&
//                                     last_heartbeat within freshness window.
//   * stopPresence()                : cancels everything (logout).
//
// Storage layout: each user has one row in the `presence` collection with
// id = user_id (PB allows custom IDs; we set them at POST time so the record
// is URL-addressable as `presence/{user_id}`). createRule restricts each user
// to creating their own row.
//
// Server-side cron sweeper in pb_hooks/presence_sweeper.pb.js flips
// is_online=false for stale rows every 30 s — replaces Firebase's
// `syncPresenceToFirestore` Cloud Function.
//
// Don't put here:
//   - SSE realtime lifecycle (foreground/background) — that's
//     PocketBaseLifecycleHook's job.
//   - Reading user.isOnline from `users` — presence lives in `presence`.
// endregion
package com.firestream.chat.data.remote.pocketbase

import android.util.Log
import com.firestream.chat.data.remote.source.PresenceSource
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class PocketBasePresenceSource @Inject constructor(
    private val client: PocketBaseClient,
    private val realtime: PocketBaseRealtime
) : PresenceSource {

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var heartbeatJob: Job? = null
    private var trackedUserId: String? = null
    private val stateLock = Any()

    /**
     * Idempotent for the same userId. For a different userId, cancels the
     * existing heartbeat first.
     */
    override fun startPresence(userId: String) {
        if (userId.isEmpty()) return
        synchronized(stateLock) {
            if (trackedUserId == userId && heartbeatJob?.isActive == true) {
                return
            }
            heartbeatJob?.cancel()
            trackedUserId = userId
            heartbeatJob = scope.launch { runHeartbeat(userId) }
        }
    }

    private suspend fun runHeartbeat(userId: String) {
        // First tick: ensure the presence record exists and write the initial
        // online state. If the record already exists (re-login or app restart),
        // POST returns 400 — fall through to a PATCH.
        ensurePresenceRecord(userId)
        // Loop exits when delay() throws CancellationException on cancel().
        while (true) {
            delay(HEARTBEAT_INTERVAL_MS)
            patchHeartbeat(userId)
        }
    }

    private suspend fun ensurePresenceRecord(userId: String) {
        val now = System.currentTimeMillis()
        val createBody = JSONObject().apply {
            put("id", userId) // custom id so the record is URL-addressable
            put("user_id", userId)
            put("is_online", true)
            put("last_heartbeat", now)
        }
        val created = runCatching {
            client.post("/api/collections/presence/records", createBody)
        }
        if (created.isSuccess) return
        // Already exists or transient failure — fall back to PATCH which is
        // also what every subsequent heartbeat does.
        patchHeartbeat(userId, isOnline = true)
    }

    private suspend fun patchHeartbeat(userId: String, isOnline: Boolean = true) {
        val body = JSONObject().apply {
            put("is_online", isOnline)
            put("last_heartbeat", System.currentTimeMillis())
        }
        runCatching { client.patch("/api/collections/presence/records/$userId", body) }
            .onFailure { Log.w(TAG, "patchHeartbeat($userId) failed: ${it.message}") }
    }

    override suspend fun goOffline(userId: String) {
        synchronized(stateLock) {
            heartbeatJob?.cancel()
            heartbeatJob = null
            trackedUserId = null
        }
        // Run the offline write under NonCancellable so a parent-scope cancel
        // (e.g. logout coroutine being torn down) doesn't drop the write.
        withContext(NonCancellable) {
            val body = JSONObject().apply {
                put("is_online", false)
                put("last_heartbeat", System.currentTimeMillis())
            }
            runCatching { client.patch("/api/collections/presence/records/$userId", body) }
        }
    }

    /**
     * Live online indicator. Prefixed with a best-effort REST GET so the UI
     * doesn't sit on `false` until the next SSE event. Belt-and-suspenders
     * freshness check ([FRESHNESS_WINDOW_MS]) covers the gap between the
     * heartbeat stalling and the cron sweeper running.
     */
    override fun observeOnlineStatus(userId: String): Flow<Boolean> = channelFlow {
        // Initial value from REST.
        val initial = runCatching {
            client.get("/api/collections/presence/records/$userId")
        }.getOrNull()
        send(initial?.let(::derivePresence) ?: false)

        // SSE tail.
        realtime.subscribe("presence/$userId")
            .filterIsInstance<RealtimeEvent.Record>()
            .collect { event ->
                if (event.action == "delete") {
                    send(false)
                    return@collect
                }
                val record = event.data.optJSONObject("record") ?: return@collect
                send(derivePresence(record))
            }
    }

    internal fun derivePresence(record: JSONObject): Boolean {
        if (!record.optBoolean("is_online", false)) return false
        val lastHeartbeat = record.optLong("last_heartbeat", 0L)
        return System.currentTimeMillis() - lastHeartbeat < FRESHNESS_WINDOW_MS
    }

    override fun stopPresence() {
        synchronized(stateLock) {
            heartbeatJob?.cancel()
            heartbeatJob = null
            trackedUserId = null
        }
    }

    companion object {
        private const val TAG = "PbPresence"
        internal const val HEARTBEAT_INTERVAL_MS = 20_000L
        internal const val FRESHNESS_WINDOW_MS = 60_000L
    }
}
