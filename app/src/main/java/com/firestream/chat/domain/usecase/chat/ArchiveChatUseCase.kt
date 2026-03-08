package com.firestream.chat.domain.usecase.chat

import com.firestream.chat.domain.repository.ChatRepository
import javax.inject.Inject

class ArchiveChatUseCase @Inject constructor(
    private val chatRepository: ChatRepository
) {
    suspend operator fun invoke(chatId: String, archived: Boolean): Result<Unit> =
        chatRepository.archiveChat(chatId, archived)
}
