package com.firestream.chat.domain.usecase.call

import com.firestream.chat.domain.repository.CallRepository
import javax.inject.Inject

class AnswerCallUseCase @Inject constructor(
    private val callRepository: CallRepository
) {
    suspend operator fun invoke(callId: String): Result<Unit> =
        callRepository.answerCall(callId)
}
