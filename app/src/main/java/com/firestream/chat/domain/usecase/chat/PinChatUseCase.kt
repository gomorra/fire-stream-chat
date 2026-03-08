package com.firestream.chat.domain.usecase.chat

import com.firestream.chat.domain.repository.ChatRepository
import javax.inject.Inject

class PinChatUseCase @Inject constructor(
    private val chatRepository: ChatRepository
) {
    suspend operator fun invoke(chatId: String, pinned: Boolean): Result<Unit> =
        chatRepository.pinChat(chatId, pinned)
}
