package com.firestream.chat.domain.usecase.chat

import com.firestream.chat.domain.repository.ChatRepository
import javax.inject.Inject

class UpdateGroupUseCase @Inject constructor(
    private val chatRepository: ChatRepository
) {
    suspend operator fun invoke(chatId: String, name: String?, avatarUrl: String?): Result<Unit> {
        return chatRepository.updateGroup(chatId, name, avatarUrl)
    }
}
