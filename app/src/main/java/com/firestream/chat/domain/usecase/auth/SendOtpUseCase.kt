package com.firestream.chat.domain.usecase.auth

import com.firestream.chat.domain.repository.AuthRepository
import javax.inject.Inject

class SendOtpUseCase @Inject constructor(
    private val authRepository: AuthRepository
) {
    suspend operator fun invoke(phoneNumber: String): Result<String> {
        return authRepository.sendOtp(phoneNumber)
    }
}
