package com.firestream.chat.data.local.entity

import androidx.room.Entity
import androidx.room.PrimaryKey
import androidx.room.TypeConverters
import com.firestream.chat.data.local.Converters
import com.firestream.chat.domain.model.Message
import com.firestream.chat.domain.model.MessageStatus
import com.firestream.chat.domain.model.MessageType
import com.firestream.chat.domain.model.ListDiff
import com.firestream.chat.domain.model.Poll
import com.firestream.chat.domain.model.PollOption
import org.json.JSONArray
import org.json.JSONObject

@Entity(tableName = "messages")
@TypeConverters(Converters::class)
data class MessageEntity(
    @PrimaryKey val id: String,
    val chatId: String,
    val senderId: String,
    val content: String,
    val type: String,
    val mediaUrl: String?,
    val mediaThumbnailUrl: String?,
    val localUri: String? = null,
    val mediaWidth: Int? = null,
    val mediaHeight: Int? = null,
    val status: String,
    val replyToId: String?,
    val timestamp: Long,
    val editedAt: Long?,
    // Phase 1 additions
    val reactions: Map<String, String> = emptyMap(),
    val isForwarded: Boolean = false,
    val duration: Int? = null,
    // Phase 2: starred messages
    val isStarred: Boolean = false,
    val readBy: Map<String, Long> = emptyMap(),
    val deliveredTo: Map<String, Long> = emptyMap(),
    val pollData: String? = null,
    val mentions: List<String> = emptyList(),
    val deletedAt: Long? = null,
    val emojiSizes: Map<Int, Float> = emptyMap(),
    val listId: String? = null,
    val listDiff: String? = null,
    val isPinned: Boolean = false
) {
    fun toDomain() = Message(
        id = id,
        chatId = chatId,
        senderId = senderId,
        content = content,
        type = runCatching { MessageType.valueOf(type) }.getOrDefault(MessageType.TEXT),
        mediaUrl = mediaUrl,
        mediaThumbnailUrl = mediaThumbnailUrl,
        localUri = localUri,
        mediaWidth = mediaWidth,
        mediaHeight = mediaHeight,
        status = runCatching { MessageStatus.valueOf(status) }.getOrDefault(MessageStatus.SENT),
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
        isPinned = isPinned
    )

    companion object {
        fun fromDomain(message: Message) = MessageEntity(
            id = message.id,
            chatId = message.chatId,
            senderId = message.senderId,
            content = message.content,
            type = message.type.name,
            mediaUrl = message.mediaUrl,
            mediaThumbnailUrl = message.mediaThumbnailUrl,
            localUri = message.localUri,
            mediaWidth = message.mediaWidth,
            mediaHeight = message.mediaHeight,
            status = message.status.name,
            replyToId = message.replyToId,
            timestamp = message.timestamp,
            editedAt = message.editedAt,
            reactions = message.reactions,
            isForwarded = message.isForwarded,
            duration = message.duration,
            isStarred = message.isStarred,
            readBy = message.readBy,
            deliveredTo = message.deliveredTo,
            pollData = message.pollData?.let { pollToJson(it) },
            mentions = message.mentions,
            deletedAt = message.deletedAt,
            emojiSizes = message.emojiSizes,
            listId = message.listId,
            listDiff = message.listDiff?.let { listDiffToJson(it) },
            isPinned = message.isPinned
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
