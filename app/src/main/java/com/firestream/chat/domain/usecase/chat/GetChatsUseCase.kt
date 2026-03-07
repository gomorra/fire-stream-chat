package com.firestream.chat.domain.usecase.chat

import com.firestream.chat.domain.model.Chat
import com.firestream.chat.domain.repository.ChatRepository
import kotlinx.coroutines.flow.Flow
import javax.inject.Inject

class GetChatsUseCase @Inject constructor(
    private val chatRepository: ChatRepository
) {
    operator fun invoke(): Flow<List<Chat>> {
        return chatRepository.getChats()
    }
}
