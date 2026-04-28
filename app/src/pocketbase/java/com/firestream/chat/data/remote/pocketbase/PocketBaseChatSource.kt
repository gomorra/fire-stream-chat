// region: AGENT-NOTE
// PocketBase chat source — REST + SSE on the `chats` collection.
//
// SSE strategy: subscribe to the whole `chats` collection; PB enforces the
// listRule (`participants ~ @request.auth.id`) so unrelated chats never reach
// the client. Each event mutates an in-memory cache keyed by chat id, then we
// emit the full list. Acceptable load for v0 walking-skeleton (handful of
// chats per user); revisit if/when group sizes spike.
//
// addToArrayField / removeFromArrayField are read-modify-write under a per-
// chat Mutex (PB has no native arrayUnion/arrayRemove for json fields). For
// 1:1 chats the contention is effectively zero; group flows are out of v0
// scope, so the race window is acceptable.
//
// Don't put here:
//   - Group invite-link / approval / ownership flows — stub for v0.
//   - Typing indicators or per-user unread — neither lives in pb_schema.json
//     v0; methods stay no-op.
//   - lastMessage* writes from the message source — those go through
//     updateChatFields with PB-shaped keys (last_message_id / preview / at).
// endregion
package com.firestream.chat.data.remote.pocketbase

import com.firestream.chat.data.remote.source.ChatSource
import com.firestream.chat.domain.model.Chat
import com.firestream.chat.domain.model.ChatType
import com.firestream.chat.domain.model.Message
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONArray
import org.json.JSONObject
import java.net.URLEncoder
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class PocketBaseChatSource @Inject constructor(
    private val client: PocketBaseClient,
    private val realtime: PocketBaseRealtime
) : ChatSource {

    /** Per-chat lock for read-modify-write on json array fields. */
    private val arrayFieldLocks = ConcurrentHashMap<String, Mutex>()
    private fun lockFor(chatId: String): Mutex =
        arrayFieldLocks.getOrPut(chatId) { Mutex() }

    /**
     * Initial REST page (filtered server-side by listRule) plus a tail of
     * SSE events. PB delivers `create`/`update`/`delete` for any record the
     * user is allowed to see; we mutate a local cache and re-emit the list.
     */
    override fun observeChatsForUser(uid: String): Flow<List<Chat>> = channelFlow {
        val cache = LinkedHashMap<String, Chat>()
        val mutex = Mutex()

        suspend fun emitSnapshot() {
            val list = mutex.withLock { cache.values.toList() }
            send(list)
        }

        // Initial fetch — PB returns all chats this user can list.
        runCatching {
            val response = client.get("/api/collections/chats/records?perPage=200&sort=-last_message_at")
            val items = response.optJSONArray("items") ?: JSONArray()
            mutex.withLock {
                for (i in 0 until items.length()) {
                    val record = items.getJSONObject(i)
                    val chat = mapChat(record, uid)
                    cache[chat.id] = chat
                }
            }
        }
        emitSnapshot()

        launch {
            realtime.subscribe("chats").collect { event ->
                if (event !is RealtimeEvent.Record) return@collect
                val record = event.data.optJSONObject("record") ?: return@collect
                val id = record.optString("id").takeIf { it.isNotEmpty() } ?: return@collect
                mutex.withLock {
                    when (event.action) {
                        "delete" -> cache.remove(id)
                        else -> cache[id] = mapChat(record, uid)
                    }
                }
                emitSnapshot()
            }
        }
    }

    /** Typing indicators are not modelled in v0 PB schema. */
    override fun observeTypingUsers(chatId: String): Flow<List<String>> = emptyFlow()

    override suspend fun getChat(chatId: String, currentUid: String): Chat? {
        val record = runCatching {
            client.get("/api/collections/chats/records/$chatId")
        }.getOrNull() ?: return null
        return mapChat(record, currentUid)
    }

    /**
     * Looks up an INDIVIDUAL chat for the sorted participant pair. PB's `~`
     * operator on json arrays does substring match; we constrain to two
     * `participants ~ "..."` clauses joined by `&&`.
     */
    override suspend fun findIndividualChat(participants: List<String>, currentUid: String): Chat? {
        if (participants.isEmpty()) return null
        val clauses = participants.joinToString(" && ") { uid -> "participants ~ \"$uid\"" }
        val filter = "type=\"${ChatType.INDIVIDUAL.name}\" && $clauses"
        val response = runCatching {
            client.get("/api/collections/chats/records?perPage=1&filter=${urlEncode(filter)}")
        }.getOrNull() ?: return null
        val items = response.optJSONArray("items") ?: return null
        if (items.length() == 0) return null
        return mapChat(items.getJSONObject(0), currentUid)
    }

    override suspend fun getChatIdForInviteToken(token: String): String? = null

    override suspend fun createChat(data: Map<String, Any?>): String {
        val body = JSONObject(translateChatWriteFields(data))
        val response = client.post("/api/collections/chats/records", body)
        return response.optString("id")
    }

    override suspend fun updateChatFields(chatId: String, updates: Map<String, Any?>) {
        val translated = translateChatWriteFields(updates)
        if (translated.isEmpty()) return
        client.patch("/api/collections/chats/records/$chatId", JSONObject(translated))
    }

    override suspend fun deleteChatDocument(chatId: String) {
        client.delete("/api/collections/chats/records/$chatId")
    }

    /**
     * Read-modify-write on a json array field. Per-chat Mutex serialises
     * concurrent updates from this process; cross-process races are
     * theoretically possible but acceptable for v0 (1:1 chats only).
     */
    override suspend fun addToArrayField(chatId: String, field: String, value: Any) {
        val pbField = translateArrayField(field)
        lockFor(chatId).withLock {
            val record = client.get("/api/collections/chats/records/$chatId")
            val current = record.optJSONArray(pbField) ?: JSONArray()
            if (jsonArrayContains(current, value)) return@withLock
            current.put(value)
            client.patch(
                "/api/collections/chats/records/$chatId",
                JSONObject().put(pbField, current)
            )
        }
    }

    override suspend fun removeFromArrayField(chatId: String, field: String, value: Any) {
        val pbField = translateArrayField(field)
        lockFor(chatId).withLock {
            val record = client.get("/api/collections/chats/records/$chatId")
            val current = record.optJSONArray(pbField) ?: return@withLock
            val next = JSONArray()
            for (i in 0 until current.length()) {
                val item = current.get(i)
                if (item != value) next.put(item)
            }
            client.patch(
                "/api/collections/chats/records/$chatId",
                JSONObject().put(pbField, next)
            )
        }
    }

    // ── No-op / out-of-scope group flows ────────────────────────────────────

    override suspend fun setTyping(chatId: String, uid: String, isTyping: Boolean) {
        // No-op: typing indicators not in v0 PB schema.
    }

    override suspend fun resetUnreadCount(chatId: String, uid: String) {
        // No-op: per-user unread counts not in v0 PB schema.
    }

    override suspend fun setInviteLink(chatId: String, token: String, createdBy: String) {
        throw NotImplementedError("PB v0: group invite links deferred")
    }

    override suspend fun clearInviteLink(chatId: String, existingToken: String?) {
        throw NotImplementedError("PB v0: group invite links deferred")
    }

    override suspend fun getExistingInviteToken(chatId: String): String? = null

    override suspend fun approveMember(chatId: String, userId: String) {
        throw NotImplementedError("PB v0: group approval deferred")
    }

    override suspend fun leaveGroupPromotingAdmin(
        chatId: String,
        leavingUid: String,
        newAdminUid: String
    ) {
        throw NotImplementedError("PB v0: group flows deferred")
    }

    override suspend fun leaveGroupRemovingSelf(chatId: String, leavingUid: String) {
        throw NotImplementedError("PB v0: group flows deferred")
    }

    override suspend fun transferOwnership(chatId: String, newOwnerId: String) {
        throw NotImplementedError("PB v0: group ownership transfer deferred")
    }

    // ── Helpers ─────────────────────────────────────────────────────────────

    private fun mapChat(record: JSONObject, currentUid: String): Chat {
        val id = record.optString("id")
        val typeName = record.optString("type").takeIf { it.isNotEmpty() } ?: ChatType.INDIVIDUAL.name
        val type = runCatching { ChatType.valueOf(typeName) }.getOrDefault(ChatType.INDIVIDUAL)

        val participantsArr = record.optJSONArray("participants") ?: JSONArray()
        val participants = (0 until participantsArr.length())
            .mapNotNull { participantsArr.optString(it).takeIf { s -> s.isNotEmpty() } }

        val preview = record.optString("last_message_preview").takeIf { it.isNotEmpty() }
        val lastTs = record.optLong("last_message_at", 0L).takeIf { it > 0L }
        val lastMessage = if (preview != null && lastTs != null) {
            Message(id = "", chatId = id, senderId = "", content = preview, timestamp = lastTs)
        } else null

        return Chat(
            id = id,
            type = type,
            participants = participants,
            lastMessage = lastMessage,
            createdAt = lastTs ?: 0L
        )
    }

    /**
     * Translate a Firebase-shaped chat field map to the PB schema. Drops keys
     * that have no v0 column (admins, owner, createdAt, createdBy, name,
     * description, inviteLink, requireApproval, pendingMembers, permissions,
     * unreadCounts, typingUsers).
     */
    private fun translateChatWriteFields(updates: Map<String, Any?>): Map<String, Any?> {
        val out = LinkedHashMap<String, Any?>()
        for ((k, v) in updates) {
            val pbKey = when (k) {
                "type", "participants" -> k
                "lastMessageId" -> "last_message_id"
                "lastMessageContent" -> "last_message_preview"
                "lastMessageTimestamp" -> "last_message_at"
                else -> continue
            }
            out[pbKey] = v
        }
        return out
    }

    private fun translateArrayField(field: String): String = when (field) {
        "participants" -> "participants"
        // v0 schema only models `participants`. admins/pendingMembers etc. are
        // group flows the plan explicitly defers; throw rather than silently
        // mutate the wrong column.
        else -> throw NotImplementedError("PB v0: array field '$field' not supported (group flows deferred)")
    }

    private fun jsonArrayContains(arr: JSONArray, value: Any): Boolean {
        for (i in 0 until arr.length()) if (arr.get(i) == value) return true
        return false
    }

    private fun urlEncode(s: String): String =
        URLEncoder.encode(s, "UTF-8")
}
