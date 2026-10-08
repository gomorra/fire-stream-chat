package com.firestream.chat.data.call

/**
 * What a [CallSession] needs from Android. [CallService] provides it: the foreground service and its
 * notification, the audio session, the microphone permission, and a [CallMedia] per call.
 */
internal interface CallHost {
    fun hasMicrophonePermission(): Boolean

    /** The saved copy of [userId]'s profile picture, or null when there is none. */
    fun localAvatarPath(userId: String): String?

    /** Run in the foreground while this phone rings someone. */
    fun foregroundOutgoing(remoteName: String)

    /** Run in the foreground while this phone rings. The microphone is not in use yet. */
    fun foregroundIncoming(remoteName: String)

    /** Run in the foreground as a microphone service, for a call this phone has just answered. */
    fun foregroundOngoing(remoteName: String)

    /** Show the call as ongoing, for a call the other phone has just answered. */
    fun showOngoing(remoteName: String)

    fun createMedia(callId: String, listener: CallMedia.Listener): CallMedia

    /** Take audio focus and start routing. Idempotent. */
    fun startAudioSession()

    /** Undo [startAudioSession]. Safe when it never ran. */
    fun stopAudioSession()

    /** [session] has ended and released everything. Drop its notifications and let the service stop. */
    fun onSessionFinished(session: CallSession)
}
