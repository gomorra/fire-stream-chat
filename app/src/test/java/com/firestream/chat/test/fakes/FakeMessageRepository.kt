package com.firestream.chat.test.fakes

import com.firestream.chat.domain.model.ListDiff
import com.firestream.chat.domain.model.Message
import com.firestream.chat.domain.repository.MessageRepository
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.emptyFlow

/**
 * In-memory fake [MessageRepository] that lets tests push messages via
 * [emitMessages]. Unused methods throw.
 */
class FakeMessageRepository : MessageRepository {

    private val messagesFlow = MutableSharedFlow<List<Message>>(
        replay = 1,
        extraBufferCapacity = 1,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )

    /** Calls made to [markMessagesAsRead]; list of (chatId, ids) pairs. */
    val markAsReadCalls: MutableList<Pair<String, List<String>>> = mutableListOf()

    /** Calls made to [markMessagesAsDelivered]. */
    val markAsDeliveredCalls: MutableList<Pair<String, List<String>>> = mutableListOf()

    private val _uploadProgress = MutableStateFlow<Map<String, Float>>(emptyMap())
    override val uploadProgress: StateFlow<Map<String, Float>> = _uploadProgress.asStateFlow()

    fun emitMessages(messages: List<Message>) {
        messagesFlow.tryEmit(messages)
    }

    override fun getMessages(chatId: String): Flow<List<Message>> = messagesFlow

    override suspend fun markMessagesAsRead(
        chatId: String,
        messageIds: List<String>,
    ): Result<Unit> {
        markAsReadCalls.add(chatId to messageIds)
        return Result.success(Unit)
    }

    override suspend fun markMessagesAsDelivered(
        chatId: String,
        messageIds: List<String>,
    ): Result<Unit> {
        markAsDeliveredCalls.add(chatId to messageIds)
        return Result.success(Unit)
    }

    override suspend fun markChatAsDelivered(chatId: String): Result<Unit> =
        Result.success(Unit)

    override fun getStarredMessages(): Flow<List<Message>> = emptyFlow()

    override fun getSharedMedia(chatId: String): Flow<List<Message>> = emptyFlow()

    override fun getSharedMediaForUser(userId: String): Flow<List<Message>> = emptyFlow()

    override fun getCallLog(): Flow<List<Message>> = emptyFlow()

    override suspend fun syncAllChatMessages(chatIds: List<String>) = Unit

    // --- Unused by current tests; throw to surface accidental usage. ---

    override suspend fun sendMessage(
        chatId: String,
        content: String,
        recipientId: String,
        replyToId: String?,
        mentions: List<String>,
        emojiSizes: Map<Int, Float>,
    ): Result<Message> = error("FakeMessageRepository.sendMessage not implemented")

    override suspend fun deleteMessage(chatId: String, messageId: String): Result<Unit> =
        error("FakeMessageRepository.deleteMessage not implemented")

    override suspend fun updateMessageStatus(
        chatId: String,
        messageId: String,
        status: String,
    ): Result<Unit> = error("FakeMessageRepository.updateMessageStatus not implemented")

    override suspend fun editMessage(
        chatId: String,
        messageId: String,
        newContent: String,
    ): Result<Unit> = error("FakeMessageRepository.editMessage not implemented")

    override suspend fun sendMediaMessage(
        chatId: String,
        uri: String,
        mimeType: String,
        recipientId: String,
        caption: String,
    ): Result<Message> = error("FakeMessageRepository.sendMediaMessage not implemented")

    override suspend fun addReaction(
        chatId: String,
        messageId: String,
        userId: String,
        emoji: String,
    ): Result<Unit> = error("FakeMessageRepository.addReaction not implemented")

    override suspend fun removeReaction(
        chatId: String,
        messageId: String,
        userId: String,
    ): Result<Unit> = error("FakeMessageRepository.removeReaction not implemented")

    override suspend fun forwardMessage(
        message: Message,
        targetChatId: String,
        recipientId: String,
    ): Result<Message> = error("FakeMessageRepository.forwardMessage not implemented")

    override suspend fun sendVoiceMessage(
        chatId: String,
        uri: String,
        recipientId: String,
        durationSeconds: Int,
    ): Result<Message> = error("FakeMessageRepository.sendVoiceMessage not implemented")

    override suspend fun starMessage(messageId: String, starred: Boolean): Result<Unit> =
        error("FakeMessageRepository.starMessage not implemented")

    override suspend fun searchMessages(query: String): List<Message> =
        error("FakeMessageRepository.searchMessages not implemented")

    override suspend fun searchMessagesInChat(chatId: String, query: String): List<Message> =
        error("FakeMessageRepository.searchMessagesInChat not implemented")

    override suspend fun sendBroadcastMessage(
        broadcastChatId: String,
        content: String,
        recipientIds: List<String>,
    ): Result<Message> = error("FakeMessageRepository.sendBroadcastMessage not implemented")

    override suspend fun sendListMessage(
        chatId: String,
        listId: String,
        listTitle: String,
        listDiff: ListDiff?,
    ): Result<Message> = error("FakeMessageRepository.sendListMessage not implemented")

    override suspend fun pinMessage(
        chatId: String,
        messageId: String,
        pinned: Boolean,
    ): Result<Unit> = error("FakeMessageRepository.pinMessage not implemented")

    override suspend fun sendLocationMessage(
        chatId: String,
        latitude: Double,
        longitude: Double,
        recipientId: String,
        comment: String,
    ): Result<Message> = error("FakeMessageRepository.sendLocationMessage not implemented")
}
