package com.firestream.chat.domain.usecase.message

import com.firestream.chat.domain.model.Message
import com.firestream.chat.domain.repository.MessageRepository
import javax.inject.Inject

class SendBroadcastMessageUseCase @Inject constructor(
    private val messageRepository: MessageRepository
) {
    suspend operator fun invoke(
        broadcastChatId: String,
        content: String,
        recipientIds: List<String>
    ): Result<Message> {
        return messageRepository.sendBroadcastMessage(broadcastChatId, content, recipientIds)
    }
}
