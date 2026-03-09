package com.firestream.chat.domain.usecase.message

import com.firestream.chat.domain.repository.MessageRepository
import javax.inject.Inject

class VotePollUseCase @Inject constructor(
    private val messageRepository: MessageRepository
) {
    suspend operator fun invoke(
        chatId: String,
        messageId: String,
        optionIds: List<String>
    ): Result<Unit> {
        return messageRepository.votePoll(chatId, messageId, optionIds)
    }
}
