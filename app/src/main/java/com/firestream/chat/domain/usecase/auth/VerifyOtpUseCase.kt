package com.firestream.chat.domain.usecase.auth

import com.firestream.chat.domain.model.User
import com.firestream.chat.domain.repository.AuthRepository
import javax.inject.Inject

class VerifyOtpUseCase @Inject constructor(
    private val authRepository: AuthRepository
) {
    suspend operator fun invoke(verificationId: String, otp: String): Result<User> {
        return authRepository.verifyOtp(verificationId, otp)
    }
}
