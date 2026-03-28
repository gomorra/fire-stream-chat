package com.firestream.chat.domain.usecase.message

import com.firestream.chat.domain.repository.PollRepository
import javax.inject.Inject

class ClosePollUseCase @Inject constructor(
    private val pollRepository: PollRepository
) {
    suspend operator fun invoke(chatId: String, messageId: String): Result<Unit> {
        return pollRepository.closePoll(chatId, messageId)
    }
}
