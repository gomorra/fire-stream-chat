package com.firestream.chat.domain.usecase.chat

import com.firestream.chat.domain.model.Chat
import com.firestream.chat.domain.repository.ChatRepository
import javax.inject.Inject

class CreateGroupUseCase @Inject constructor(
    private val chatRepository: ChatRepository
) {
    suspend operator fun invoke(name: String, participantIds: List<String>): Result<Chat> {
        return chatRepository.createGroup(name, participantIds)
    }
}
