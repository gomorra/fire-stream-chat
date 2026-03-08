package com.firestream.chat.domain.usecase.message

import com.firestream.chat.domain.repository.MessageRepository
import javax.inject.Inject

class StarMessageUseCase @Inject constructor(
    private val messageRepository: MessageRepository
) {
    suspend operator fun invoke(messageId: String, starred: Boolean): Result<Unit> =
        messageRepository.starMessage(messageId, starred)
}
