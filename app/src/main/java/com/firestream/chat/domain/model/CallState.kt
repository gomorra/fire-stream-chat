package com.firestream.chat.domain.model

sealed interface CallState {
    data object Idle : CallState

    data class OutgoingRinging(
        val callId: String,
        val calleeId: String,
        val calleeName: String,
        val calleeAvatarUrl: String?,
        val calleeLocalAvatarPath: String? = null
    ) : CallState

    data class IncomingRinging(
        val callId: String,
        val callerId: String,
        val callerName: String,
        val callerAvatarUrl: String?,
        val callerLocalAvatarPath: String? = null
    ) : CallState

    data class Connecting(
        val callId: String,
        val remoteUserId: String,
        val remoteName: String,
        val remoteAvatarUrl: String?,
        val remoteLocalAvatarPath: String? = null
    ) : CallState

    data class Connected(
        val callId: String,
        val remoteUserId: String,
        val remoteName: String,
        val remoteAvatarUrl: String?,
        val startTime: Long,
        val remoteLocalAvatarPath: String? = null
    ) : CallState

    data class Ended(
        val callId: String,
        val reason: EndReason
    ) : CallState
}

enum class EndReason {
    HANGUP,
    REMOTE_HANGUP,
    DECLINED,
    TIMEOUT,
    ERROR
}

data class CallUiControls(
    val isMuted: Boolean = false,
    val isSpeakerOn: Boolean = false
)
