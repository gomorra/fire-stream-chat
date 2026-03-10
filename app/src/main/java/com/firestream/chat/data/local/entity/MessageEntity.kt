package com.firestream.chat.data.local.entity

import androidx.room.Entity
import androidx.room.PrimaryKey
import androidx.room.TypeConverters
import com.firestream.chat.data.local.Converters
import com.firestream.chat.domain.model.Message
import com.firestream.chat.domain.model.MessageStatus
import com.firestream.chat.domain.model.MessageType
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
    val deletedAt: Long? = null
) {
    fun toDomain() = Message(
        id = id,
        chatId = chatId,
        senderId = senderId,
        content = content,
        type = runCatching { MessageType.valueOf(type) }.getOrDefault(MessageType.TEXT),
        mediaUrl = mediaUrl,
        mediaThumbnailUrl = mediaThumbnailUrl,
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
        deletedAt = deletedAt
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
            deletedAt = message.deletedAt
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
