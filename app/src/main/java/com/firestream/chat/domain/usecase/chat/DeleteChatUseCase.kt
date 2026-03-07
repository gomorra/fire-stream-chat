package com.firestream.chat.domain.usecase.chat

import com.firestream.chat.domain.repository.ChatRepository
import javax.inject.Inject

class DeleteChatUseCase @Inject constructor(
    private val chatRepository: ChatRepository
) {
    suspend operator fun invoke(chatId: String): Result<Unit> {
        return chatRepository.deleteChat(chatId)
    }
}
