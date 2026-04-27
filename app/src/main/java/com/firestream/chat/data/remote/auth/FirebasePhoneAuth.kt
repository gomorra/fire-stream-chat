package com.firestream.chat.data.remote.auth

import android.app.Activity
import com.firestream.chat.domain.model.AppError
import com.google.firebase.FirebaseException
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.PhoneAuthCredential
import com.google.firebase.auth.PhoneAuthOptions
import com.google.firebase.auth.PhoneAuthProvider
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Wraps `PhoneAuthProvider.verifyPhoneNumber` (callback API) into a
 * `Flow<OtpEvent>` so the VM can collect it without owning the Firebase types.
 *
 * Lives in `data/remote/auth/` rather than `data/remote/firebase/` because both
 * Gradle flavors use Firebase Phone OTP for login — the pocketbase flavor
 * exchanges the resulting ID token for a PB session via the bridge endpoint
 * instead of staying on Firebase, but the OTP step itself is the same.
 *
 * Activity is required by `PhoneAuthOptions.setActivity` for the reCAPTCHA
 * fallback path. Keep it out of `AuthRepository` (domain) — the VM injects
 * this helper directly and keeps the Android type at the UI layer.
 */
@Singleton
class FirebasePhoneAuth @Inject constructor(
    private val auth: FirebaseAuth
) {
    fun send(phoneNumber: String, activity: Activity): Flow<OtpEvent> = callbackFlow {
        val callbacks = object : PhoneAuthProvider.OnVerificationStateChangedCallbacks() {
            override fun onVerificationCompleted(credential: PhoneAuthCredential) {
                trySend(OtpEvent.AutoVerified)
            }

            override fun onVerificationFailed(e: FirebaseException) {
                trySend(OtpEvent.Failed(AppError.from(e)))
            }

            override fun onCodeSent(
                verificationId: String,
                token: PhoneAuthProvider.ForceResendingToken
            ) {
                trySend(OtpEvent.CodeSent(verificationId))
            }
        }

        val options = PhoneAuthOptions.newBuilder(auth)
            .setPhoneNumber(phoneNumber)
            .setTimeout(60L, TimeUnit.SECONDS)
            .setActivity(activity)
            .setCallbacks(callbacks)
            .build()

        PhoneAuthProvider.verifyPhoneNumber(options)

        // Firebase doesn't expose a way to cancel an in-flight verifyPhoneNumber.
        // The callbacks settle within 60s; keeping the channel open until then is
        // benign, and any new send() call kicks off a fresh verification attempt.
        awaitClose { /* no-op */ }
    }
}
