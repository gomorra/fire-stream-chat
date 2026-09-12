package com.firestream.chat.data.local.entity

import androidx.room.ColumnInfo
import androidx.room.Embedded
import androidx.room.Entity
import androidx.room.TypeConverters
import com.firestream.chat.data.local.Converters
import com.firestream.chat.data.outbox.SendTarget
import com.firestream.chat.domain.model.Message
import com.firestream.chat.data.util.parseMessageStatus
import com.firestream.chat.data.util.parseMessageType
import com.firestream.chat.data.util.parseTimerAlarmSound
import com.firestream.chat.data.util.parseTimerAlarmStyle
import com.firestream.chat.data.util.parseTimerState
import com.firestream.chat.domain.model.ListDiff
import com.firestream.chat.domain.model.Poll
import com.firestream.chat.domain.model.TimerAlarmSound
import com.firestream.chat.domain.model.TimerAlarmStyle
import com.firestream.chat.domain.model.PollOption
import org.json.JSONArray
import org.json.JSONObject

/**
 * A row of `messages`: the backend's [record] of the message, plus what only
 * this device knows about it.
 *
 * The split is the write contract. A snapshot or a sync upserts a
 * [MessageRecord] (`MessageDao.upsertRecord`) and by construction cannot touch
 * anything below it; the local columns change only through column updates, the
 * outbox insert of an own send ([outbox] → `MessageDao.insertOutbox`) and the
 * SENT transaction (`MessageDao.markSent`). Nothing needs to remember to preserve
 * a local column when it writes what the backend said.
 */
@Entity(tableName = "messages", primaryKeys = ["id"])
@TypeConverters(Converters::class)
data class MessageEntity(
    @Embedded val record: MessageRecord,
    /** A file on this device: the input of an own send until it is uploaded, or the downloaded copy of a received one. */
    val localUri: String? = null,
    @ColumnInfo(defaultValue = "0")
    val isStarred: Boolean = false,
    // Offline outbox bookkeeping for an own row that has not reached SENT.
    // Written by the outbox insert and OutboxSender's column updates, cleared by
    // MessageDao.clearOutbox — the one statement inside the SENT transaction and
    // the acknowledged-echo heal.
    /** The 1:1 peer to encrypt for; "" for group and broadcast chats; null = not recorded. */
    val outboxRecipientId: String? = null,
    /** Signal ciphertext of `content`, encrypted once and reused by every later attempt. */
    val outboxCiphertext: String? = null,
    val outboxSignalType: Int? = null,
    /** The peer identity `outboxCiphertext` was encrypted for; a retry reuses the bytes only while the peer still publishes it. */
    val outboxPeerIdentity: String? = null,
    /** Pipeline runs started for this row; above 0, an earlier write may already have landed. */
    @ColumnInfo(defaultValue = "0")
    val outboxAttempts: Int = 0,
) : MessageColumns by record {

    fun toDomain(): Message = record.toDomain(localUri = localUri, isStarred = isStarred)

    /** Who the outbox sends this row to; `null` when the row never recorded a target and must not be sent. */
    val sendTarget: SendTarget?
        get() = SendTarget.fromColumn(outboxRecipientId)

    companion object {
        /** The whole row for [message] with no outbox bookkeeping — for reads and tests; a send inserts through [outbox]. */
        fun fromDomain(message: Message) = MessageEntity(
            record = MessageRecord.fromDomain(message),
            localUri = message.localUri,
            isStarred = message.isStarred,
        )

        /** The optimistic row of a send the outbox will deliver, with the target it encrypts for. */
        fun outbox(message: Message, target: SendTarget) =
            fromDomain(message).copy(outboxRecipientId = target.column)
    }
}

/**
 * Every column of a message the backend holds, as one read-only view shared by
 * [MessageRecord] (which stores them) and [MessageEntity] (which delegates to it,
 * so `entity.status` reads as it always did).
 */
interface MessageColumns {
    val id: String
    val chatId: String
    val senderId: String
    val content: String
    val type: String
    val mediaUrl: String?
    val mediaThumbnailUrl: String?
    val mediaWidth: Int?
    val mediaHeight: Int?
    val status: String
    val replyToId: String?
    val timestamp: Long
    val editedAt: Long?
    val reactions: Map<String, String>
    val isForwarded: Boolean
    val duration: Int?
    val readBy: Map<String, Long>
    val deliveredTo: Map<String, Long>
    val pollData: String?
    val mentions: List<String>
    val deletedAt: Long?
    val emojiSizes: Map<Int, Float>
    val listId: String?
    val listDiff: String?
    val isPinned: Boolean
    val latitude: Double?
    val longitude: Double?
    val isHd: Boolean
    val timerDurationMs: Long?
    val timerStartedAtMs: Long?
    val timerState: String?
    val timerRemainingMs: Long?
    val timerAlarmStyle: String
    val timerAlarmSound: String
}

/**
 * The columns of a message the backend holds — Room's partial entity for
 * `messages` (`MessageDao.upsertRecord`). An upsert of one inserts the row when
 * it is new and otherwise overwrites exactly these columns, so the local ones on
 * [MessageEntity] survive every snapshot and sync write without being copied.
 */
@TypeConverters(Converters::class)
data class MessageRecord(
    override val id: String,
    override val chatId: String,
    override val senderId: String,
    override val content: String,
    override val type: String,
    override val mediaUrl: String?,
    override val mediaThumbnailUrl: String?,
    override val mediaWidth: Int? = null,
    override val mediaHeight: Int? = null,
    override val status: String,
    override val replyToId: String?,
    override val timestamp: Long,
    override val editedAt: Long?,
    // Phase 1 additions
    override val reactions: Map<String, String> = emptyMap(),
    override val isForwarded: Boolean = false,
    override val duration: Int? = null,
    override val readBy: Map<String, Long> = emptyMap(),
    override val deliveredTo: Map<String, Long> = emptyMap(),
    override val pollData: String? = null,
    override val mentions: List<String> = emptyList(),
    override val deletedAt: Long? = null,
    override val emojiSizes: Map<Int, Float> = emptyMap(),
    override val listId: String? = null,
    override val listDiff: String? = null,
    override val isPinned: Boolean = false,
    override val latitude: Double? = null,
    override val longitude: Double? = null,
    override val isHd: Boolean = false,
    override val timerDurationMs: Long? = null,
    override val timerStartedAtMs: Long? = null,
    override val timerState: String? = null,
    override val timerRemainingMs: Long? = null,
    // Enum names, not the legacy timerSilent boolean: v25 is reached by destructive
    // migration, so no row here predates the alarm enums and there is nothing to
    // fall back from. Legacy resolution belongs at the remote boundary only.
    override val timerAlarmStyle: String = TimerAlarmStyle.DEFAULT.name,
    override val timerAlarmSound: String = TimerAlarmSound.DEFAULT.name,
) : MessageColumns {

    /** The domain message, with the local columns the caller holds beside this record. */
    fun toDomain(localUri: String? = null, isStarred: Boolean = false) = Message(
        id = id,
        chatId = chatId,
        senderId = senderId,
        content = content,
        type = parseMessageType(type),
        mediaUrl = mediaUrl,
        mediaThumbnailUrl = mediaThumbnailUrl,
        localUri = localUri,
        mediaWidth = mediaWidth,
        mediaHeight = mediaHeight,
        status = parseMessageStatus(status),
        replyToId = replyToId,
        timestamp = timestamp,
        editedAt = editedAt,
        reactions = reactions,
        isForwarded = isForwarded,
        duration = duration,
        isStarred = isStarred,
        readBy = readBy,
        deliveredTo = deliveredTo,
        pollData = pollData?.let { parsePollJson(it) },
        mentions = mentions,
        deletedAt = deletedAt,
        emojiSizes = emojiSizes,
        listId = listId,
        listDiff = listDiff?.let { parseListDiffJson(it) },
        isPinned = isPinned,
        latitude = latitude,
        longitude = longitude,
        isHd = isHd,
        timerDurationMs = timerDurationMs,
        timerStartedAtMs = timerStartedAtMs,
        timerState = timerState?.let { parseTimerState(it) },
        timerRemainingMs = timerRemainingMs,
        timerAlarmStyle = parseTimerAlarmStyle(timerAlarmStyle) ?: TimerAlarmStyle.DEFAULT,
        timerAlarmSound = parseTimerAlarmSound(timerAlarmSound) ?: TimerAlarmSound.DEFAULT,
    )

    companion object {
        /** [message]'s backend columns; its `localUri` and `isStarred` are local and not carried. */
        fun fromDomain(message: Message) = MessageRecord(
            id = message.id,
            chatId = message.chatId,
            senderId = message.senderId,
            content = message.content,
            type = message.type.name,
            mediaUrl = message.mediaUrl,
            mediaThumbnailUrl = message.mediaThumbnailUrl,
            mediaWidth = message.mediaWidth,
            mediaHeight = message.mediaHeight,
            status = message.status.name,
            replyToId = message.replyToId,
            timestamp = message.timestamp,
            editedAt = message.editedAt,
            reactions = message.reactions,
            isForwarded = message.isForwarded,
            duration = message.duration,
            readBy = message.readBy,
            deliveredTo = message.deliveredTo,
            pollData = message.pollData?.let { pollToJson(it) },
            mentions = message.mentions,
            deletedAt = message.deletedAt,
            emojiSizes = message.emojiSizes,
            listId = message.listId,
            listDiff = message.listDiff?.let { listDiffToJson(it) },
            isPinned = message.isPinned,
            latitude = message.latitude,
            longitude = message.longitude,
            isHd = message.isHd,
            timerDurationMs = message.timerDurationMs,
            timerStartedAtMs = message.timerStartedAtMs,
            timerState = message.timerState?.name,
            timerRemainingMs = message.timerRemainingMs,
            timerAlarmStyle = message.timerAlarmStyle.name,
            timerAlarmSound = message.timerAlarmSound.name,
        )

        private fun pollToJson(poll: Poll): String {
            val obj = JSONObject().apply {
                put("question", poll.question)
                put("isMultipleChoice", poll.isMultipleChoice)
                put("isAnonymous", poll.isAnonymous)
                put("isClosed", poll.isClosed)
                put("options", JSONArray().apply {
                    poll.options.forEach { option ->
                        put(JSONObject().apply {
                            put("id", option.id)
                            put("text", option.text)
                            put("voterIds", JSONArray(option.voterIds))
                        })
                    }
                })
            }
            return obj.toString()
        }

        private fun listDiffToJson(diff: ListDiff): String {
            return JSONObject().apply {
                put("added", JSONArray(diff.added))
                put("removed", JSONArray(diff.removed))
                put("checked", JSONArray(diff.checked))
                put("unchecked", JSONArray(diff.unchecked))
                if (diff.edited.isNotEmpty()) put("edited", JSONArray(diff.edited))
                if (diff.titleChanged != null) put("titleChanged", diff.titleChanged)
                if (diff.deleted) put("deleted", true)
                if (diff.unshared) put("unshared", true)
                if (diff.shared) put("shared", true)
            }.toString()
        }

        private fun parseListDiffJson(json: String): ListDiff? {
            return try {
                val obj = JSONObject(json)
                ListDiff(
                    added = jsonArrayToStringList(obj.optJSONArray("added")),
                    removed = jsonArrayToStringList(obj.optJSONArray("removed")),
                    checked = jsonArrayToStringList(obj.optJSONArray("checked")),
                    unchecked = jsonArrayToStringList(obj.optJSONArray("unchecked")),
                    edited = jsonArrayToStringList(obj.optJSONArray("edited")),
                    titleChanged = obj.optString("titleChanged", null).takeIf { it != "null" },
                    deleted = obj.optBoolean("deleted", false),
                    unshared = obj.optBoolean("unshared", false),
                    shared = obj.optBoolean("shared", false)
                )
            } catch (_: Exception) {
                null
            }
        }

        private fun jsonArrayToStringList(arr: JSONArray?): List<String> {
            if (arr == null) return emptyList()
            return List(arr.length()) { i -> arr.getString(i) }
        }

        private fun parsePollJson(json: String): Poll? {
            return try {
                val obj = JSONObject(json)
                val optionsArr = obj.getJSONArray("options")
                val options = List(optionsArr.length()) { i ->
                    val o = optionsArr.getJSONObject(i)
                    val voterIds = o.optJSONArray("voterIds")?.let { arr ->
                        List(arr.length()) { j -> arr.getString(j) }
                    } ?: emptyList()
                    PollOption(
                        id = o.getString("id"),
                        text = o.getString("text"),
                        voterIds = voterIds
                    )
                }
                Poll(
                    question = obj.getString("question"),
                    options = options,
                    isMultipleChoice = obj.optBoolean("isMultipleChoice", false),
                    isAnonymous = obj.optBoolean("isAnonymous", false),
                    isClosed = obj.optBoolean("isClosed", false)
                )
            } catch (_: Exception) {
                null
            }
        }
    }
}
