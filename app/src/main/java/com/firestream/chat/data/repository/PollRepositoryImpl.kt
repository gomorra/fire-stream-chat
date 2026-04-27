package com.firestream.chat.data.repository

import com.firestream.chat.data.local.dao.ChatDao
import com.firestream.chat.data.local.dao.MessageDao
import com.firestream.chat.data.local.entity.MessageEntity
import com.firestream.chat.data.remote.source.AuthSource
import com.firestream.chat.data.remote.source.MessageSource
import com.firestream.chat.domain.model.Message
import com.firestream.chat.domain.model.MessageStatus
import com.firestream.chat.domain.model.MessageType
import com.firestream.chat.domain.model.Poll
import com.firestream.chat.domain.model.PollOption
import com.firestream.chat.domain.repository.PollRepository
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class PollRepositoryImpl @Inject constructor(
    private val messageDao: MessageDao,
    private val chatDao: ChatDao,
    private val messageSource: MessageSource,
    private val authSource: AuthSource
) : PollRepository {

    override suspend fun sendPoll(
        chatId: String,
        question: String,
        options: List<String>,
        isMultipleChoice: Boolean,
        isAnonymous: Boolean
    ): Result<Message> {
        return try {
            val senderId = authSource.currentUserId ?: throw Exception("Not authenticated")
            val timestamp = System.currentTimeMillis()

            val pollOptions = options.mapIndexed { index, text ->
                PollOption(id = "opt_$index", text = text)
            }
            val poll = Poll(
                question = question,
                options = pollOptions,
                isMultipleChoice = isMultipleChoice,
                isAnonymous = isAnonymous
            )

            val pollDataMap = buildPollFirestoreMap(poll)

            val remoteId = messageSource.sendPollMessage(
                chatId = chatId,
                senderId = senderId,
                pollData = pollDataMap,
                timestamp = timestamp
            )

            val pollPreview = messageSource.lastContentFor(MessageType.POLL)
            val message = Message(
                id = remoteId,
                chatId = chatId,
                senderId = senderId,
                content = pollPreview,
                type = MessageType.POLL,
                status = MessageStatus.SENT,
                timestamp = timestamp,
                pollData = poll
            )
            messageDao.insertMessage(MessageEntity.fromDomain(message))
            chatDao.updateLastMessage(chatId, remoteId, pollPreview, timestamp)

            Result.success(message)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    override suspend fun votePoll(chatId: String, messageId: String, optionIds: List<String>): Result<Unit> {
        return try {
            val userId = authSource.currentUserId ?: throw Exception("Not authenticated")
            val entity = messageDao.getMessageById(messageId) ?: throw Exception("Message not found")
            val message = entity.toDomain()
            val poll = message.pollData ?: throw Exception("Not a poll message")
            if (poll.isClosed) throw Exception("Poll is closed")

            messageSource.votePoll(chatId, messageId, userId, optionIds)

            val updatedOptions = poll.options.map { option ->
                val voters = option.voterIds.toMutableList()
                if (optionIds.contains(option.id)) {
                    if (!voters.contains(userId)) voters.add(userId)
                } else if (!poll.isMultipleChoice) {
                    voters.remove(userId)
                }
                option.copy(voterIds = voters)
            }
            val updatedPoll = poll.copy(options = updatedOptions)
            val updatedMessage = message.copy(pollData = updatedPoll)
            messageDao.insertMessage(MessageEntity.fromDomain(updatedMessage))

            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    override suspend fun closePoll(chatId: String, messageId: String): Result<Unit> {
        return try {
            messageSource.closePoll(chatId, messageId)

            val entity = messageDao.getMessageById(messageId)
            if (entity != null) {
                val msg = entity.toDomain()
                val updatedPoll = msg.pollData?.copy(isClosed = true)
                messageDao.insertMessage(MessageEntity.fromDomain(msg.copy(pollData = updatedPoll)))
            }

            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
}
