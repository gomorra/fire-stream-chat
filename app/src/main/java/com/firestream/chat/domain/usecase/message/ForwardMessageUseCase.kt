package com.firestream.chat.domain.usecase.message

import com.firestream.chat.domain.model.Message
import com.firestream.chat.domain.repository.MessageRepository
import javax.inject.Inject

class ForwardMessageUseCase @Inject constructor(
    private val messageRepository: MessageRepository
) {
    suspend operator fun invoke(
        message: Message,
        targetChatId: String,
        recipientId: String
    ): Result<Message> = messageRepository.forwardMessage(message, targetChatId, recipientId)
}
