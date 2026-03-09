package com.firestream.chat.domain.usecase.chat

import com.firestream.chat.domain.repository.ChatRepository
import javax.inject.Inject

class DemoteFromAdminUseCase @Inject constructor(
    private val chatRepository: ChatRepository
) {
    suspend operator fun invoke(chatId: String, userId: String): Result<Unit> {
        return chatRepository.demoteFromAdmin(chatId, userId)
    }
}
