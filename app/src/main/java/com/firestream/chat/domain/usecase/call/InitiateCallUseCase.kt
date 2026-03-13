package com.firestream.chat.domain.usecase.call

import com.firestream.chat.domain.repository.CallRepository
import javax.inject.Inject

class InitiateCallUseCase @Inject constructor(
    private val callRepository: CallRepository
) {
    suspend operator fun invoke(calleeId: String): Result<String> =
        callRepository.createCall(calleeId)
}
