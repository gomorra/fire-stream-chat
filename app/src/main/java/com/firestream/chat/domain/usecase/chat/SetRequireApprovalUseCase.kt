package com.firestream.chat.domain.usecase.chat

import com.firestream.chat.domain.repository.ChatRepository
import javax.inject.Inject

class SetRequireApprovalUseCase @Inject constructor(
    private val chatRepository: ChatRepository
) {
    suspend operator fun invoke(chatId: String, enabled: Boolean): Result<Unit> =
        chatRepository.setRequireApproval(chatId, enabled)
}
