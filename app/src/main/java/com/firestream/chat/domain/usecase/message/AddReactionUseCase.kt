package com.firestream.chat.domain.usecase.message

import com.firestream.chat.domain.repository.MessageRepository
import javax.inject.Inject

class AddReactionUseCase @Inject constructor(
    private val messageRepository: MessageRepository
) {
    suspend operator fun invoke(chatId: String, messageId: String, userId: String, emoji: String): Result<Unit> =
        messageRepository.addReaction(chatId, messageId, userId, emoji)
}
