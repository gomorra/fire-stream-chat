package com.firestream.chat.domain.usecase.chat

import com.firestream.chat.domain.repository.ChatRepository
import javax.inject.Inject

/**
 * Mutes a chat until the given epoch-ms timestamp.
 * Pass [muteUntil] = 0 to unmute; Long.MAX_VALUE to mute permanently.
 */
class MuteChatUseCase @Inject constructor(
    private val chatRepository: ChatRepository
) {
    suspend operator fun invoke(chatId: String, muteUntil: Long): Result<Unit> =
        chatRepository.muteChat(chatId, muteUntil)
}
