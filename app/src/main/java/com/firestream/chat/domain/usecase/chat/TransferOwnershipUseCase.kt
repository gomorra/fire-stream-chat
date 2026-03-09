package com.firestream.chat.domain.usecase.chat

import com.firestream.chat.domain.repository.ChatRepository
import javax.inject.Inject

class TransferOwnershipUseCase @Inject constructor(
    private val chatRepository: ChatRepository
) {
    suspend operator fun invoke(chatId: String, newOwnerId: String): Result<Unit> {
        return chatRepository.transferOwnership(chatId, newOwnerId)
    }
}
