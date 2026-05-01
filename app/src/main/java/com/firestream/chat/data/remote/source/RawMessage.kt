package com.firestream.chat.data.remote.source

/**
 * Backend-neutral message envelope crossing the [MessageSource] boundary.
 *
 * [ciphertext] + [signalType] are populated by Firebase 1:1 sends that go
 * through Signal; [content] is populated for plaintext (group / debug / pocketbase
 * flavor). At least one of those two channels must be non-null when [deletedAt]
 * is null — the repo skips messages that satisfy neither.
 *
 * Both backends populate this from their wire representation:
 * - Firebase: `chats/{chatId}/messages/{id}` document fields.
 * - PocketBase: `messages` collection records (always plaintext in v0; the
 *   `ciphertext`/`signalType` fields stay null because `BuildConfig.SUPPORTS_SIGNAL`
 *   is false in the pocketbase flavor).
 */
data class RawMessage(
    val id: String,
    val chatId: String,
    val senderId: String,
    val content: String?,
    val ciphertext: String?,
    val signalType: Int?,
    val type: String,
    val mediaUrl: String?,
    val mediaThumbnailUrl: String?,
    val status: String,
    val replyToId: String?,
    val timestamp: Long,
    val editedAt: Long?,
    val reactions: Map<String, String> = emptyMap(),
    val isForwarded: Boolean = false,
    val duration: Int? = null,
    val readBy: Map<String, Long> = emptyMap(),
    val deliveredTo: Map<String, Long> = emptyMap(),
    val pollData: Map<String, Any?>? = null,
    val mentions: List<String> = emptyList(),
    val deletedAt: Long? = null,
    val emojiSizes: Map<Int, Float> = emptyMap(),
    val listId: String? = null,
    val listDiff: Map<String, Any?>? = null,
    val isPinned: Boolean = false,
    val mediaWidth: Int? = null,
    val mediaHeight: Int? = null,
    val latitude: Double? = null,
    val longitude: Double? = null,
    val isHd: Boolean = false
)
