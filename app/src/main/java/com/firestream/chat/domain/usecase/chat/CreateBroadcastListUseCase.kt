package com.firestream.chat.domain.usecase.chat

import com.firestream.chat.domain.model.Chat
import com.firestream.chat.domain.repository.ChatRepository
import javax.inject.Inject

class CreateBroadcastListUseCase @Inject constructor(
    private val chatRepository: ChatRepository
) {
    suspend operator fun invoke(name: String, recipientIds: List<String>): Result<Chat> {
        return chatRepository.createBroadcastList(name, recipientIds)
    }
}
