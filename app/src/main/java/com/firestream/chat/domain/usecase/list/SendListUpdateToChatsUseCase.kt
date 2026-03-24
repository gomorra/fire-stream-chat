package com.firestream.chat.domain.usecase.list

import com.firestream.chat.domain.model.ListDiff
import com.firestream.chat.domain.repository.MessageRepository
import javax.inject.Inject

class SendListUpdateToChatsUseCase @Inject constructor(
    private val messageRepository: MessageRepository
) {
    suspend operator fun invoke(
        listId: String,
        listTitle: String,
        sharedChatIds: List<String>,
        diff: ListDiff
    ) {
        for (chatId in sharedChatIds) {
            try {
                messageRepository.sendListMessage(chatId, listId, listTitle, diff)
            } catch (_: Exception) {
                // Best-effort delivery to each chat
            }
        }
    }
}
