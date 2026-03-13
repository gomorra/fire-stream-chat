package com.firestream.chat.domain.usecase.call

import com.firestream.chat.domain.repository.CallRepository
import javax.inject.Inject

class EndCallUseCase @Inject constructor(
    private val callRepository: CallRepository
) {
    suspend operator fun invoke(callId: String, reason: String = "caller_hangup"): Result<Unit> =
        callRepository.endCall(callId, reason)
}
