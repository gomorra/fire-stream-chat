package com.firestream.chat.domain.usecase.message

import com.firestream.chat.domain.model.Message
import com.firestream.chat.domain.repository.PollRepository
import javax.inject.Inject

class SendPollUseCase @Inject constructor(
    private val pollRepository: PollRepository
) {
    suspend operator fun invoke(
        chatId: String,
        question: String,
        options: List<String>,
        isMultipleChoice: Boolean,
        isAnonymous: Boolean
    ): Result<Message> {
        return pollRepository.sendPoll(chatId, question, options, isMultipleChoice, isAnonymous)
    }
}
