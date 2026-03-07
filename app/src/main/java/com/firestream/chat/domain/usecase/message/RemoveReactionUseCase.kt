package com.firestream.chat.domain.usecase.message

import com.firestream.chat.domain.repository.MessageRepository
import javax.inject.Inject

class RemoveReactionUseCase @Inject constructor(
    private val messageRepository: MessageRepository
) {
    suspend operator fun invoke(chatId: String, messageId: String, userId: String): Result<Unit> =
        messageRepository.removeReaction(chatId, messageId, userId)
}
