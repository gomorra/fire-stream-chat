package com.firestream.chat.domain.usecase.message

import com.firestream.chat.domain.model.Message
import com.firestream.chat.domain.repository.MessageRepository
import javax.inject.Inject

class SendMessageUseCase @Inject constructor(
    private val messageRepository: MessageRepository
) {
    suspend operator fun invoke(
        chatId: String,
        content: String,
        recipientId: String,
        replyToId: String? = null,
        mentions: List<String> = emptyList()
    ): Result<Message> {
        return messageRepository.sendMessage(chatId, content, recipientId, replyToId, mentions)
    }
}
