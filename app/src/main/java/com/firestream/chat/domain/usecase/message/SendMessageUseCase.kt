package com.firestream.chat.domain.usecase.message

import com.firestream.chat.domain.model.Message
import com.firestream.chat.domain.repository.MessageRepository
import javax.inject.Inject

class SendMessageUseCase @Inject constructor(
    private val messageRepository: MessageRepository
) {
    suspend operator fun invoke(chatId: String, content: String, recipientId: String): Result<Message> {
        return messageRepository.sendMessage(chatId, content, recipientId)
    }
}
