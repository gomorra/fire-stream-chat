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

/**
 * Where the audio of an ongoing call is playing.
 *
 * Declaration order **is** display order — `availableRoutes` is sorted by `ordinal`, so
 * reordering these constants reorders the route sheet. Keep EARPIECE first.
 */
enum class CallAudioRoute {
    EARPIECE,
    SPEAKER,
    BLUETOOTH,
    WIRED_HEADSET
}

data class CallUiControls(
    val isMuted: Boolean = false,
    val audioRoute: CallAudioRoute = CallAudioRoute.EARPIECE,
    val availableRoutes: List<CallAudioRoute> = listOf(CallAudioRoute.EARPIECE, CallAudioRoute.SPEAKER)
)
