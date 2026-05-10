package com.firestream.chat.test.fakes

import com.firestream.chat.domain.model.ListDiff
import com.firestream.chat.domain.model.Message
import com.firestream.chat.domain.model.MessageStatus
import com.firestream.chat.domain.repository.MessageRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.map
import java.util.UUID

internal class FakeMessageRepository : MessageRepository {

    private val messagesByChat = MutableStateFlow<Map<String, List<Message>>>(emptyMap())
    private val starred = MutableStateFlow<Set<String>>(emptySet())

    private val _uploadProgress = MutableStateFlow<Map<String, Float>>(emptyMap())
    override val uploadProgress: StateFlow<Map<String, Float>> = _uploadProgress.asStateFlow()

    var nextFailure: Throwable? = null

    val markAsReadCalls: MutableList<Pair<String, List<String>>> = mutableListOf()
    val markAsDeliveredCalls: MutableList<Pair<String, List<String>>> = mutableListOf()
    val retryCalls: MutableList<Pair<String, String>> = mutableListOf()
    var lastSentMessage: Message? = null
    var lastSentRecipientId: String? = null

    fun emit(chatId: String, messages: List<Message>) {
        messagesByChat.value = messagesByChat.value + (chatId to messages)
    }

    fun reset() {
        messagesByChat.value = emptyMap()
        starred.value = emptySet()
        _uploadProgress.value = emptyMap()
        nextFailure = null
        markAsReadCalls.clear()
        markAsDeliveredCalls.clear()
        retryCalls.clear()
        lastSentMessage = null
        lastSentRecipientId = null
    }

    private fun consumeFailure(): Result<Nothing>? =
        nextFailure?.also { nextFailure = null }?.let { Result.failure(it) }

    private fun throwIfNextFailure() {
        nextFailure?.also { nextFailure = null }?.let { throw it }
    }

    // ── Core flows ────────────────────────────────────────────────────────────

    override fun getMessages(chatId: String): Flow<List<Message>> =
        messagesByChat.map { it[chatId].orEmpty() }

    override fun getStarredMessages(): Flow<List<Message>> =
        messagesByChat.map { byChat ->
            byChat.values.flatten().filter { it.isStarred || it.id in starred.value }
        }

    override fun getSharedMedia(chatId: String): Flow<List<Message>> =
        messagesByChat.map { it[chatId].orEmpty().filter { m -> m.mediaUrl != null } }

    override fun getSharedMediaForUser(userId: String): Flow<List<Message>> =
        messagesByChat.map { byChat ->
            byChat.values.flatten().filter { it.senderId == userId && it.mediaUrl != null }
        }

    override fun getCallLog(): Flow<List<Message>> = emptyFlow()

    // ── Send / mutate ─────────────────────────────────────────────────────────

    override suspend fun sendMessage(
        chatId: String,
        content: String,
        recipientId: String,
        replyToId: String?,
        mentions: List<String>,
        emojiSizes: Map<Int, Float>,
    ): Result<Message> {
        consumeFailure()?.let { return it }
        val msg = Message(
            id = UUID.randomUUID().toString(),
            chatId = chatId,
            senderId = "me",
            content = content,
            replyToId = replyToId,
            mentions = mentions,
            emojiSizes = emojiSizes,
            status = MessageStatus.SENT,
        )
        lastSentMessage = msg
        lastSentRecipientId = recipientId
        messagesByChat.value = messagesByChat.value.toMutableMap().also { map ->
            map[chatId] = (map[chatId].orEmpty()) + msg
        }
        return Result.success(msg)
    }

    override suspend fun deleteMessage(chatId: String, messageId: String): Result<Unit> {
        consumeFailure()?.let { return it }
        messagesByChat.value = messagesByChat.value.toMutableMap().also { map ->
            map[chatId] = map[chatId].orEmpty().filter { it.id != messageId }
        }
        return Result.success(Unit)
    }

    override suspend fun updateMessageStatus(
        chatId: String,
        messageId: String,
        status: String,
    ): Result<Unit> {
        consumeFailure()?.let { return it }
        val newStatus = MessageStatus.entries.firstOrNull { it.name == status }
        if (newStatus != null) {
            messagesByChat.value = messagesByChat.value.toMutableMap().also { map ->
                map[chatId] = map[chatId].orEmpty().map { m ->
                    if (m.id == messageId) m.copy(status = newStatus) else m
                }
            }
        }
        return Result.success(Unit)
    }

    override suspend fun editMessage(
        chatId: String,
        messageId: String,
        newContent: String,
    ): Result<Unit> {
        consumeFailure()?.let { return it }
        messagesByChat.value = messagesByChat.value.toMutableMap().also { map ->
            map[chatId] = map[chatId].orEmpty().map { m ->
                if (m.id == messageId) m.copy(content = newContent) else m
            }
        }
        return Result.success(Unit)
    }

    override suspend fun sendMediaMessage(
        chatId: String,
        uri: String,
        mimeType: String,
        recipientId: String,
        caption: String,
    ): Result<Message> {
        consumeFailure()?.let { return it }
        val msg = Message(id = UUID.randomUUID().toString(), chatId = chatId, content = caption)
        lastSentMessage = msg
        return Result.success(msg)
    }

    override suspend fun addReaction(
        chatId: String,
        messageId: String,
        userId: String,
        emoji: String,
    ): Result<Unit> {
        consumeFailure()?.let { return it }
        return Result.success(Unit)
    }

    override suspend fun removeReaction(
        chatId: String,
        messageId: String,
        userId: String,
    ): Result<Unit> {
        consumeFailure()?.let { return it }
        return Result.success(Unit)
    }

    override suspend fun forwardMessage(
        message: Message,
        targetChatId: String,
        recipientId: String,
    ): Result<Message> {
        consumeFailure()?.let { return it }
        val forwarded = message.copy(id = UUID.randomUUID().toString(), chatId = targetChatId, isForwarded = true)
        lastSentMessage = forwarded
        return Result.success(forwarded)
    }

    override suspend fun sendVoiceMessage(
        chatId: String,
        uri: String,
        recipientId: String,
        durationSeconds: Int,
    ): Result<Message> {
        consumeFailure()?.let { return it }
        val msg = Message(id = UUID.randomUUID().toString(), chatId = chatId, duration = durationSeconds)
        lastSentMessage = msg
        return Result.success(msg)
    }

    override suspend fun starMessage(messageId: String, starred: Boolean): Result<Unit> {
        consumeFailure()?.let { return it }
        this.starred.value = if (starred) this.starred.value + messageId else this.starred.value - messageId
        return Result.success(Unit)
    }

    override suspend fun searchMessages(query: String): List<Message> {
        throwIfNextFailure()
        return messagesByChat.value.values.flatten().filter { it.content.contains(query, ignoreCase = true) }
    }

    override suspend fun searchMessagesInChat(chatId: String, query: String): List<Message> {
        throwIfNextFailure()
        return messagesByChat.value[chatId].orEmpty().filter { it.content.contains(query, ignoreCase = true) }
    }

    override suspend fun markChatAsDelivered(chatId: String): Result<Unit> {
        consumeFailure()?.let { return it }
        return Result.success(Unit)
    }

    override suspend fun markMessagesAsDelivered(
        chatId: String,
        messageIds: List<String>,
    ): Result<Unit> {
        consumeFailure()?.let { return it }
        markAsDeliveredCalls.add(chatId to messageIds)
        return Result.success(Unit)
    }

    override suspend fun markMessagesAsRead(
        chatId: String,
        messageIds: List<String>,
    ): Result<Unit> {
        consumeFailure()?.let { return it }
        markAsReadCalls.add(chatId to messageIds)
        return Result.success(Unit)
    }

    override suspend fun sendBroadcastMessage(
        broadcastChatId: String,
        content: String,
        recipientIds: List<String>,
    ): Result<Message> {
        consumeFailure()?.let { return it }
        val msg = Message(id = UUID.randomUUID().toString(), chatId = broadcastChatId, content = content)
        lastSentMessage = msg
        return Result.success(msg)
    }

    override suspend fun sendListMessage(
        chatId: String,
        listId: String,
        listTitle: String,
        listDiff: ListDiff?,
    ): Result<Message> {
        consumeFailure()?.let { return it }
        val msg = Message(id = UUID.randomUUID().toString(), chatId = chatId, listId = listId)
        lastSentMessage = msg
        return Result.success(msg)
    }

    override suspend fun pinMessage(chatId: String, messageId: String, pinned: Boolean): Result<Unit> {
        consumeFailure()?.let { return it }
        return Result.success(Unit)
    }

    override suspend fun sendLocationMessage(
        chatId: String,
        latitude: Double,
        longitude: Double,
        recipientId: String,
        comment: String,
    ): Result<Message> {
        consumeFailure()?.let { return it }
        val msg = Message(
            id = UUID.randomUUID().toString(),
            chatId = chatId,
            latitude = latitude,
            longitude = longitude,
            content = comment,
        )
        lastSentMessage = msg
        return Result.success(msg)
    }

    override suspend fun syncAllChatMessages(chatIds: List<String>) = Unit

    override suspend fun sendTimerMessage(
        chatId: String,
        durationMs: Long,
        caption: String?,
        recipientId: String,
        silent: Boolean,
    ): Result<Message> {
        consumeFailure()?.let { return it }
        val msg = Message(
            id = UUID.randomUUID().toString(),
            chatId = chatId,
            type = com.firestream.chat.domain.model.MessageType.TIMER,
            content = caption.orEmpty(),
            timerDurationMs = durationMs,
            timerStartedAtMs = System.currentTimeMillis(),
            timerState = com.firestream.chat.domain.model.TimerState.RUNNING,
        )
        lastSentMessage = msg
        return Result.success(msg)
    }

    override suspend fun cancelTimer(chatId: String, messageId: String): Result<Unit> {
        consumeFailure()?.let { return it }
        return Result.success(Unit)
    }

    override suspend fun markTimerCompleted(chatId: String, messageId: String): Result<Unit> {
        consumeFailure()?.let { return it }
        return Result.success(Unit)
    }

    override suspend fun pauseTimer(
        chatId: String,
        messageId: String,
        remainingMs: Long,
    ): Result<Unit> {
        consumeFailure()?.let { return it }
        return Result.success(Unit)
    }

    override suspend fun resumeTimer(chatId: String, messageId: String): Result<Unit> {
        consumeFailure()?.let { return it }
        return Result.success(Unit)
    }

    override suspend fun retryFailedMessage(messageId: String, recipientId: String): Result<Message> {
        retryCalls.add(messageId to recipientId)
        consumeFailure()?.let { return it }
        // Find the failed message across all chats; flip it to SENT in place.
        val matching = messagesByChat.value
            .asSequence()
            .flatMap { (cid, msgs) -> msgs.asSequence().map { cid to it } }
            .firstOrNull { (_, msg) -> msg.id == messageId }
            ?: return Result.failure(IllegalStateException("Cannot retry unknown message $messageId"))
        val (chatId, original) = matching
        if (original.status != MessageStatus.FAILED) {
            return Result.failure(IllegalStateException("Cannot retry message in state ${original.status}"))
        }
        val sent = original.copy(status = MessageStatus.SENT)
        messagesByChat.value = messagesByChat.value.toMutableMap().also { map ->
            map[chatId] = map[chatId].orEmpty().map { if (it.id == messageId) sent else it }
        }
        return Result.success(sent)
    }
}
