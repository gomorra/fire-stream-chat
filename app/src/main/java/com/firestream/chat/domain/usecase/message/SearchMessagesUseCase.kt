package com.firestream.chat.domain.usecase.message

import com.firestream.chat.domain.model.MessageSearchFilter
import com.firestream.chat.domain.model.MessageSearchResults
import com.firestream.chat.domain.repository.MessageRepository
import javax.inject.Inject

class SearchMessagesUseCase @Inject constructor(
    private val messageRepository: MessageRepository
) {
    /**
     * Searches one chat ([chatId] non-null) or every chat (null), with an
     * optional [filter] either way.
     *
     * A blank query with an active filter is browse mode ("show me the
     * photos"), so the blank-query guard only bites when there is no filter
     * either — a blank query with no filter would otherwise match everything
     * in scope.
     */
    suspend operator fun invoke(
        query: String,
        chatId: String? = null,
        filter: MessageSearchFilter = MessageSearchFilter.NONE,
    ): MessageSearchResults {
        // Trimmed so a query of pure whitespace reaches the data layer as the
        // empty string its browse-mode short-circuit tests for.
        val trimmed = query.trim()
        if (trimmed.isEmpty() && !filter.isActive) return MessageSearchResults.EMPTY
        return messageRepository.searchMessages(chatId, trimmed, filter)
    }
}
