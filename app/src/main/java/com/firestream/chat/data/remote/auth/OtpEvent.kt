package com.firestream.chat.data.remote.auth

import com.firestream.chat.domain.model.AppError

/**
 * Events emitted by the Firebase Phone OTP send flow. Both flavors use this —
 * the firebase variant verifies the resulting credential against Firebase Auth
 * directly, the pocketbase variant exchanges the resulting Firebase ID token
 * for a PB session via the bridge endpoint.
 */
sealed class OtpEvent {
    /** Firebase has sent the SMS code; pass [verificationId] to `AuthRepository.verifyOtp`. */
    data class CodeSent(val verificationId: String) : OtpEvent()

    /** Firebase auto-verified without requiring user-typed OTP. Rare on most devices. */
    data object AutoVerified : OtpEvent()

    /** OTP send failed (rate limit, invalid number, network, etc.). */
    data class Failed(val error: AppError) : OtpEvent()
}
