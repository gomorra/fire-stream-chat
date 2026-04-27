package com.firestream.chat.ui.auth

import android.app.Activity
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.firestream.chat.data.remote.auth.FirebasePhoneAuth
import com.firestream.chat.data.remote.auth.OtpEvent
import com.firestream.chat.domain.model.AppError
import com.firestream.chat.domain.model.User
import com.firestream.chat.domain.repository.AuthRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import javax.inject.Inject

data class AuthUiState(
    val isLoading: Boolean = false,
    val isLoggedIn: Boolean = false,
    val isCheckingAuth: Boolean = true,
    val error: AppError? = null,
    val verificationId: String? = null,
    val isNewUser: Boolean = false,
    val user: User? = null
)

@HiltViewModel
class AuthViewModel @Inject constructor(
    private val authRepository: AuthRepository,
    private val firebasePhoneAuth: FirebasePhoneAuth
) : ViewModel() {

    private val _uiState = MutableStateFlow(AuthUiState())
    val uiState: StateFlow<AuthUiState> = _uiState.asStateFlow()

    init {
        checkLoginStatus()
    }

    private fun checkLoginStatus() {
        viewModelScope.launch {
            if (authRepository.isLoggedIn) {
                try {
                    val result = withTimeout(5_000) { authRepository.getCurrentUser() }
                    result.onSuccess { user ->
                        if (user != null && user.displayName.isNotEmpty()) {
                            _uiState.value = _uiState.value.copy(isLoggedIn = true, isCheckingAuth = false, user = user)
                            return@launch
                        }
                    }
                } catch (_: TimeoutCancellationException) {
                    // Network unavailable after idle/Doze — fall through to show login screen
                }
            }
            _uiState.value = _uiState.value.copy(isCheckingAuth = false)
        }
    }

    fun sendOtp(phoneNumber: String, activity: Activity, onCodeSent: (String) -> Unit) {
        _uiState.value = _uiState.value.copy(isLoading = true, error = null)
        viewModelScope.launch {
            firebasePhoneAuth.send(phoneNumber, activity).collect { event ->
                when (event) {
                    is OtpEvent.CodeSent -> {
                        _uiState.value = _uiState.value.copy(
                            isLoading = false,
                            verificationId = event.verificationId
                        )
                        onCodeSent(event.verificationId)
                    }
                    OtpEvent.AutoVerified -> {
                        // Auto-verification (rare on most devices) — handled implicitly by
                        // FirebaseAuth's session; the user lands logged-in after the next
                        // checkLoginStatus pass.
                    }
                    is OtpEvent.Failed -> {
                        _uiState.value = _uiState.value.copy(
                            isLoading = false,
                            error = event.error
                        )
                    }
                }
            }
        }
    }

    fun verifyOtp(verificationId: String, otp: String) {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isLoading = true, error = null)

            authRepository.verifyOtp(verificationId, otp)
                .onSuccess { user ->
                    val isNew = user.displayName.isEmpty()
                    _uiState.value = _uiState.value.copy(
                        isLoading = false,
                        isNewUser = isNew,
                        user = user
                    )
                }
                .onFailure { error ->
                    _uiState.value = _uiState.value.copy(
                        isLoading = false,
                        error = AppError.from(error)
                    )
                }
        }
    }

    fun createProfile(displayName: String) {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isLoading = true, error = null)

            authRepository.createUserProfile(displayName, null)
                .onSuccess { user ->
                    _uiState.value = _uiState.value.copy(
                        isLoading = false,
                        user = user,
                        isLoggedIn = true
                    )
                }
                .onFailure { error ->
                    _uiState.value = _uiState.value.copy(
                        isLoading = false,
                        error = AppError.from(error)
                    )
                }
        }
    }

    fun clearError() {
        _uiState.value = _uiState.value.copy(error = null)
    }
}
