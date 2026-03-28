package com.firestream.chat.domain.usecase.message

import com.firestream.chat.domain.repository.PollRepository
import javax.inject.Inject

class VotePollUseCase @Inject constructor(
    private val pollRepository: PollRepository
) {
    suspend operator fun invoke(
        chatId: String,
        messageId: String,
        optionIds: List<String>
    ): Result<Unit> {
        return pollRepository.votePoll(chatId, messageId, optionIds)
    }
}
