package com.firestream.chat.domain.model

sealed interface CallState {
    data object Idle : CallState

    /** A call that is ringing, connecting or connected. */
    sealed interface Live : CallState {
        val callId: String

        /**
         * How the call was started. It sets the ring text and the call log entry. It is not the
         * live camera state.
         */
        val video: Boolean

        /** This state, for a call that was started as video. */
        fun withVideo(): Live
    }

    data class OutgoingRinging(
        override val callId: String,
        val calleeId: String,
        val calleeName: String,
        val calleeAvatarUrl: String?,
        val calleeLocalAvatarPath: String? = null,
        override val video: Boolean = false
    ) : Live {
        override fun withVideo() = copy(video = true)
    }

    data class IncomingRinging(
        override val callId: String,
        val callerId: String,
        val callerName: String,
        val callerAvatarUrl: String?,
        val callerLocalAvatarPath: String? = null,
        override val video: Boolean = false
    ) : Live {
        override fun withVideo() = copy(video = true)
    }

    data class Connecting(
        override val callId: String,
        val remoteUserId: String,
        val remoteName: String,
        val remoteAvatarUrl: String?,
        val remoteLocalAvatarPath: String? = null,
        override val video: Boolean = false
    ) : Live {
        override fun withVideo() = copy(video = true)
    }

    data class Connected(
        override val callId: String,
        val remoteUserId: String,
        val remoteName: String,
        val remoteAvatarUrl: String?,
        val startTime: Long,
        val remoteLocalAvatarPath: String? = null,
        override val video: Boolean = false
    ) : Live {
        override fun withVideo() = copy(video = true)
    }

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
