package com.firestream.chat.domain.model

sealed interface CallState {
    data object Idle : CallState

    /**
     * This phone is placing a call to [calleeId]: the call document is being created, or the call
     * is on its way to the call service. [callId] is null until the document exists. The service
     * replaces this with [OutgoingRinging]. Counts as ongoing, so no other call starts meanwhile.
     */
    data class Placing(
        val calleeId: String,
        val calleeName: String,
        val calleeAvatarUrl: String?,
        val callId: String? = null
    ) : CallState

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

/**
 * True while a call is being placed, ringing, connecting or connected.
 *
 * [CallState.Ended] is not ongoing. It is the last frame of a call that is over, kept so the call
 * screen can show "Call Ended", and it stays published until the next call replaces it. Ask this,
 * not `!is CallState.Idle`, to find out whether the user is in a call.
 */
val CallState.isOngoing: Boolean
    get() = this !is CallState.Idle && this !is CallState.Ended

enum class EndReason {
    HANGUP,
    REMOTE_HANGUP,
    DECLINED,
    TIMEOUT,
    ERROR;

    /** How the reason is written to the call document and to the call's chat message. */
    val wireName: String get() = name.lowercase()

    companion object {
        /** The reason [wireName] names, or null for one this version does not know. */
        fun fromWireName(wireName: String?): EndReason? = entries.firstOrNull { it.wireName.equals(wireName, ignoreCase = true) }
    }
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
