package com.firestream.chat.domain.usecase.chat

import com.firestream.chat.domain.repository.ChatRepository
import javax.inject.Inject

class UpdateGroupDescriptionUseCase @Inject constructor(
    private val chatRepository: ChatRepository
) {
    suspend operator fun invoke(chatId: String, description: String): Result<Unit> =
        chatRepository.updateGroupDescription(chatId, description)
}
