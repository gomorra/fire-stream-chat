package com.firestream.chat.domain.usecase.auth

import com.firestream.chat.domain.model.User
import com.firestream.chat.domain.repository.AuthRepository
import javax.inject.Inject

class GetCurrentUserUseCase @Inject constructor(
    private val authRepository: AuthRepository
) {
    suspend operator fun invoke(): Result<User?> {
        return authRepository.getCurrentUser()
    }

    val isLoggedIn: Boolean
        get() = authRepository.isLoggedIn
}
