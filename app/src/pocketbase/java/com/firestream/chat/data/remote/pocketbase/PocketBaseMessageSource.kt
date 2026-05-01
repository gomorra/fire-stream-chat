// region: AGENT-NOTE
// PocketBase message source — walking-skeleton subset only:
//   * observeMessages — initial REST page + SSE on `messages` collection;
//     filter to this chat's records locally. PB enforces listRule
//     (`chat_id.participants ~ @request.auth.id`) so unrelated chats never
//     reach the client.
//   * fetchMessages — REST GET filtered by chat_id, sorted by timestamp ASC.
//   * sendPlainMessage — POST + denormalised last_message_* writeback.
//   * lastContentFor — pure helper, mirrors Firebase impl.
//
// Everything else stays NotImplementedError per the v0 plan. The encryption
// path (sendMessage with ciphertext) is unreachable in this flavor because
// MessageRepositoryImpl gates on BuildConfig.SUPPORTS_SIGNAL=false; throwing
// makes the contract violation surface immediately if that gate ever flips.
//
// Per-user delivery/read receipts (markDelivered/markRead/getUndeliveredMessageIds)
// are no-ops because pb_schema.json v0 only has a single `status` field on
// `messages`, not per-user maps. The gap is documented in the handover.
//
// Don't put here:
//   - Reactions / polls / list-diff / pinning / editing / deleting — all
//     deferred to follow-up plans.
//   - Signal encrypt/decrypt — that's repository-layer concern.
// endregion
package com.firestream.chat.data.remote.pocketbase

import com.firestream.chat.data.remote.source.MessageSource
import com.firestream.chat.data.remote.source.RawMessage
import com.firestream.chat.domain.model.MessageStatus
import com.firestream.chat.domain.model.MessageType
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONObject
import java.net.URLEncoder
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class PocketBaseMessageSource @Inject constructor(
    private val client: PocketBaseClient,
    private val realtime: PocketBaseRealtime
) : MessageSource {

    override fun lastContentFor(type: MessageType, plain: String): String = when (type) {
        MessageType.IMAGE -> if (plain.isNotBlank()) "📷 $plain" else "📷 Photo"
        MessageType.DOCUMENT -> "📎 File"
        MessageType.VOICE -> "🎤 Voice message"
        MessageType.POLL -> "📊 Poll"
        MessageType.LIST -> if (plain.isBlank()) "📋 List" else plain
        MessageType.LOCATION -> "📍 Location"
        MessageType.CALL -> "📞 Voice call"
        else -> plain.ifBlank { "Message" }
    }

    override fun observeMessages(chatId: String): Flow<List<RawMessage>> = channelFlow {
        val cache = LinkedHashMap<String, RawMessage>()
        val mutex = Mutex()

        suspend fun emitSnapshot() {
            val list = mutex.withLock { cache.values.sortedBy { it.timestamp } }
            send(list)
        }

        runCatching { fetchMessages(chatId) }.onSuccess { initial ->
            mutex.withLock { initial.forEach { cache[it.id] = it } }
        }
        emitSnapshot()

        launch {
            realtime.subscribe("messages").collect { event ->
                if (event !is RealtimeEvent.Record) return@collect
                val record = event.data.optJSONObject("record") ?: return@collect
                if (record.optString("chat_id") != chatId) return@collect
                val id = record.optString("id").takeIf { it.isNotEmpty() } ?: return@collect
                mutex.withLock {
                    when (event.action) {
                        "delete" -> cache.remove(id)
                        else -> cache[id] = mapToRaw(record)
                    }
                }
                emitSnapshot()
            }
        }
    }

    override suspend fun fetchMessages(chatId: String): List<RawMessage> {
        val filter = "chat_id=\"$chatId\""
        val response = client.get(
            "/api/collections/messages/records?perPage=200&sort=timestamp&filter=${urlEncode(filter)}"
        )
        val items = response.optJSONArray("items") ?: return emptyList()
        return (0 until items.length()).map { mapToRaw(items.getJSONObject(it)) }
    }

    override suspend fun sendPlainMessage(
        chatId: String,
        senderId: String,
        content: String,
        type: MessageType,
        replyToId: String?,
        timestamp: Long,
        mediaUrl: String?,
        isForwarded: Boolean,
        duration: Int?,
        mentions: List<String>,
        emojiSizes: Map<Int, Float>,
        mediaWidth: Int?,
        mediaHeight: Int?,
        latitude: Double?,
        longitude: Double?,
        isHd: Boolean
    ): String {
        val body = JSONObject().apply {
            put("chat_id", chatId)
            put("sender_id", senderId)
            put("content", content)
            put("type", type.name)
            put("status", MessageStatus.SENT.name)
            if (replyToId != null) put("reply_to_id", replyToId)
            put("timestamp", timestamp)
            if (mediaUrl != null) put("media_url", mediaUrl)
        }
        val record = client.post("/api/collections/messages/records", body)
        val messageId = record.optString("id")

        // Denormalised last-message fields on the chat record. v0 schema has
        // last_message_id / last_message_preview / last_message_at; senderId is
        // not modelled on the chat row.
        val chatUpdate = JSONObject().apply {
            put("last_message_id", messageId)
            put("last_message_preview", lastContentFor(type, content))
            put("last_message_at", timestamp)
        }
        runCatching { client.patch("/api/collections/chats/records/$chatId", chatUpdate) }

        return messageId
    }

    /**
     * Encryption is gated off in the pocketbase flavor (`SUPPORTS_SIGNAL=false`),
     * so MessageRepositoryImpl.sendEncryptedOrPlain never picks the ciphertext
     * branch. If this method ever fires, something flipped the gate without
     * landing the Signal-on-PB follow-up plan — fail loud.
     */
    override suspend fun sendMessage(
        chatId: String,
        senderId: String,
        ciphertext: String,
        signalType: Int,
        type: MessageType,
        replyToId: String?,
        timestamp: Long,
        mediaUrl: String?,
        isForwarded: Boolean,
        duration: Int?,
        mentions: List<String>,
        plainContent: String,
        emojiSizes: Map<Int, Float>,
        mediaWidth: Int?,
        mediaHeight: Int?,
        latitude: Double?,
        longitude: Double?,
        isHd: Boolean
    ): String = throw NotImplementedError(
        "encryption gated off in pocketbase flavor — MessageRepositoryImpl should not reach here"
    )

    // ── Per-user delivery/read — out of scope in v0 (single status field) ───

    override suspend fun getUndeliveredMessageIds(chatId: String, currentUserId: String): List<String> =
        emptyList()

    override suspend fun markDelivered(chatId: String, messageId: String, userId: String, timestamp: Long) {
        // No-op: v0 schema has a single `status` field, not per-user maps.
    }

    override suspend fun markRead(chatId: String, messageId: String, userId: String, timestamp: Long) {
        // No-op: v0 schema has a single `status` field, not per-user maps.
    }

    // ── Everything else — deferred to follow-up plans ───────────────────────

    override suspend fun editMessage(chatId: String, messageId: String, newContent: String, editedAt: Long) =
        throw NotImplementedError("PB v0: edit deferred")

    override suspend fun deleteMessage(chatId: String, messageId: String) =
        throw NotImplementedError("PB v0: delete deferred")

    override suspend fun updateMessageStatus(chatId: String, messageId: String, status: String) =
        throw NotImplementedError("PB v0: status update deferred")

    override suspend fun sendPollMessage(
        chatId: String,
        senderId: String,
        pollData: Map<String, Any?>,
        timestamp: Long
    ): String = throw NotImplementedError("PB v0: polls deferred")

    override suspend fun votePoll(chatId: String, messageId: String, userId: String, optionIds: List<String>) =
        throw NotImplementedError("PB v0: polls deferred")

    override suspend fun closePoll(chatId: String, messageId: String) =
        throw NotImplementedError("PB v0: polls deferred")

    override suspend fun sendCallMessage(
        chatId: String,
        senderId: String,
        endReason: String,
        durationSeconds: Int,
        timestamp: Long
    ): String = throw NotImplementedError("PB v0: calls deferred")

    override suspend fun sendListMessage(
        chatId: String,
        senderId: String,
        listId: String,
        content: String,
        timestamp: Long,
        listDiff: Map<String, Any?>?
    ): String = throw NotImplementedError("PB v0: lists deferred")

    override suspend fun updateListMessageDiff(
        chatId: String,
        messageId: String,
        content: String,
        listDiff: Map<String, Any?>,
        timestamp: Long
    ) = throw NotImplementedError("PB v0: lists deferred")

    override suspend fun pinMessage(chatId: String, messageId: String, pinned: Boolean) =
        throw NotImplementedError("PB v0: pin deferred")

    override suspend fun updateReactions(chatId: String, messageId: String, reactions: Map<String, String>) =
        throw NotImplementedError("PB v0: reactions deferred")

    // ── PB record → RawMessage ──────────────────────────────────────────────

    /**
     * Field-name remap (PB → RawMessage):
     *   chat_id        → chatId (relation)
     *   sender_id      → senderId (relation)
     *   reply_to_id    → replyToId
     *   media_url      → mediaUrl
     *   timestamp/type/status/content → direct
     *
     * v0 schema has no fields for: ciphertext, signalType, mediaThumbnailUrl,
     * editedAt, reactions, isForwarded, duration, readBy, deliveredTo,
     * pollData, mentions, deletedAt, emojiSizes, listId/listDiff, isPinned,
     * mediaWidth/Height, latitude/longitude. They default to null/empty.
     */
    internal fun mapToRaw(record: JSONObject): RawMessage = RawMessage(
        id = record.optString("id"),
        chatId = record.optString("chat_id"),
        senderId = record.optString("sender_id"),
        content = record.optString("content").takeIf { it.isNotEmpty() },
        ciphertext = null,
        signalType = null,
        type = record.optString("type").ifEmpty { MessageType.TEXT.name },
        mediaUrl = record.optString("media_url").takeIf { it.isNotEmpty() },
        mediaThumbnailUrl = null,
        status = record.optString("status").ifEmpty { MessageStatus.SENT.name },
        replyToId = record.optString("reply_to_id").takeIf { it.isNotEmpty() },
        timestamp = record.optLong("timestamp", 0L),
        editedAt = null
    )

    private fun urlEncode(s: String): String =
        URLEncoder.encode(s, "UTF-8")
}
