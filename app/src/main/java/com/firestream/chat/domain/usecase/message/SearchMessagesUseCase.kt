package com.firestream.chat.domain.usecase.message

import com.firestream.chat.domain.model.Message
import com.firestream.chat.domain.repository.MessageRepository
import javax.inject.Inject

class SearchMessagesUseCase @Inject constructor(
    private val messageRepository: MessageRepository
) {
    suspend operator fun invoke(query: String, chatId: String? = null): List<Message> {
        if (query.isBlank()) return emptyList()
        return if (chatId != null) {
            messageRepository.searchMessagesInChat(chatId, query)
        } else {
            messageRepository.searchMessages(query)
        }
    }
}
