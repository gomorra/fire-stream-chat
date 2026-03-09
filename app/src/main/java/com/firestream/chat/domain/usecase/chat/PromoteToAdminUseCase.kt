package com.firestream.chat.domain.usecase.chat

import com.firestream.chat.domain.repository.ChatRepository
import javax.inject.Inject

class PromoteToAdminUseCase @Inject constructor(
    private val chatRepository: ChatRepository
) {
    suspend operator fun invoke(chatId: String, userId: String): Result<Unit> {
        return chatRepository.promoteToAdmin(chatId, userId)
    }
}
