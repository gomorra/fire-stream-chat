package com.firestream.chat.domain.usecase.message

import com.firestream.chat.domain.repository.MessageRepository
import javax.inject.Inject

class EditMessageUseCase @Inject constructor(
    private val messageRepository: MessageRepository
) {
    suspend operator fun invoke(chatId: String, messageId: String, newContent: String): Result<Unit> {
        return messageRepository.editMessage(chatId, messageId, newContent)
    }
}
