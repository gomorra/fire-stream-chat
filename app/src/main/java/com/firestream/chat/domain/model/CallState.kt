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

/**
 * The call can leave its stage and dock over its chat: it is running, and it is not a ring that
 * came in. The stage offers to minimise exactly these calls, and the chat draws exactly these.
 */
val CallState.dockable: Boolean
    get() = this is CallState.Live && this !is CallState.IncomingRinging

enum class EndReason {
    HANGUP,
    REMOTE_HANGUP,
    DECLINED,
    TIMEOUT,
    ERROR
}

/**
 * A place that can draw the running call. The camera runs only while one of them is on screen.
 */
enum class CallSurface {
    /** The full-screen stage, its picture-in-picture window included. */
    STAGE,

    /** The card or the strip over the call's chat. */
    DOCK
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

/**
 * The own side of the running call: what the user switched, and what the call allows.
 *
 * @param cameraOn the user switched the camera on. It stays true while the camera is paused.
 * @param frontCamera the camera in use is the front one.
 * @param videoAvailable false when the call has no video line both sides agreed on: the other
 *   side's app takes none. The side that places the call knows from the start. The side that
 *   answers knows once the offer is applied, and until then it is true, so the camera can be
 *   switched on while the call rings.
 * @param cameraPaused the camera is switched on but not running, because no screen shows the call.
 */
data class CallUiControls(
    val isMuted: Boolean = false,
    val audioRoute: CallAudioRoute = CallAudioRoute.EARPIECE,
    val availableRoutes: List<CallAudioRoute> = listOf(CallAudioRoute.EARPIECE, CallAudioRoute.SPEAKER),
    val cameraOn: Boolean = false,
    val frontCamera: Boolean = true,
    val videoAvailable: Boolean = true,
    val cameraPaused: Boolean = false
)

/**
 * One of the other people in the running call, and what is known of them right now.
 *
 * @param cameraOn they say their camera is sending. From the call document.
 * @param micOn they say their microphone is open. From the call document.
 * @param connected the connection to them is up.
 * @param hasFrame a frame of their video has arrived since their camera came on. A screen shows
 *   their video only when this and [cameraOn] are true, and their avatar otherwise.
 */
data class CallParticipant(
    val id: String,
    val name: String,
    val avatarUrl: String?,
    val localAvatarPath: String? = null,
    val cameraOn: Boolean = false,
    val micOn: Boolean = true,
    val connected: Boolean = false,
    val hasFrame: Boolean = false
)
