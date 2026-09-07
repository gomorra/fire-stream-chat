package com.firestream.chat.domain.usecase.message

import com.firestream.chat.domain.model.MessageSearchFilter
import com.firestream.chat.domain.model.MessageSearchResults
import com.firestream.chat.domain.repository.MessageRepository
import javax.inject.Inject

class SearchMessagesUseCase @Inject constructor(
    private val messageRepository: MessageRepository
) {
    /**
     * In-chat search ([chatId] non-null) accepts a [filter]. A blank query with
     * an active filter is browse mode ("show me the photos"), so the blank-query
     * guard only bites when there is no filter either.
     *
     * Global search (null [chatId]) has no filter axis yet — it renders results
     * across chats and would need its own result rendering. Its blank-query
     * guard is unconditional.
     */
    suspend operator fun invoke(
        query: String,
        chatId: String? = null,
        filter: MessageSearchFilter = MessageSearchFilter.NONE,
    ): MessageSearchResults {
        if (chatId == null) {
            return if (query.isBlank()) {
                MessageSearchResults.EMPTY
            } else {
                messageRepository.searchMessages(query)
            }
        }
        // Trimmed so a query of pure whitespace reaches the data layer as the
        // empty string its browse-mode short-circuit tests for.
        val trimmed = query.trim()
        if (trimmed.isEmpty() && !filter.isActive) return MessageSearchResults.EMPTY
        return messageRepository.searchMessagesInChat(chatId, trimmed, filter)
    }
}
