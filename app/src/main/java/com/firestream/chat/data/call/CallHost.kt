package com.firestream.chat.data.call

/**
 * What a [CallSession] needs from Android. [CallService] provides it: the foreground service and its
 * notification, the audio session, the permissions, and a [CallLocalMedia] per call.
 *
 * `video` is how the call was started. It only picks a notification's title.
 */
internal interface CallHost {
    fun hasMicrophonePermission(): Boolean

    fun hasCameraPermission(): Boolean

    /** Whether the phone is locked. A locked phone shows no camera preview while it rings. */
    fun isLocked(): Boolean

    /** The saved copy of [userId]'s profile picture, or null when there is none. */
    fun localAvatarPath(userId: String): String?

    /** Run in the foreground while this phone rings someone. */
    fun foregroundOutgoing(remoteName: String, video: Boolean)

    /** Run in the foreground while this phone rings. Neither the microphone nor the camera type is taken yet. */
    fun foregroundIncoming(remoteName: String, video: Boolean)

    /** Post the ring again, for a call whose kind arrived after it started to ring. */
    fun showIncoming(remoteName: String, video: Boolean)

    /**
     * Run in the foreground as a microphone service, for a call this phone has just answered, and
     * as a camera service too when [camera].
     *
     * @return false when [camera] was asked for and the system refused it. The service then runs
     *   as a microphone service alone.
     */
    fun foregroundOngoing(remoteName: String, video: Boolean, camera: Boolean): Boolean

    /** Show the call as ongoing, for a call the other phone has just answered. */
    fun showOngoing(remoteName: String, video: Boolean)

    /**
     * Name the camera in the foreground type of a call past its incoming ring, or drop it again.
     * On Android 14 and later the system refuses the camera type without the `CAMERA` permission
     * and while the app is not visible.
     *
     * @return false when the system refused to name the camera. The service keeps the type it had.
     */
    fun setForegroundCamera(camera: Boolean): Boolean

    fun createLocalMedia(listener: CallLocalMedia.Listener): CallLocalMedia

    /** Take audio focus and start routing. Idempotent. */
    fun startAudioSession()

    /**
     * Audio follows video. With [preferSpeaker] the call plays on the speaker unless a headset is
     * connected or the user picked a route. While [videoShowing] the proximity lock is off. Kept
     * for an audio session that starts later.
     */
    fun setAudioFollowsVideo(preferSpeaker: Boolean, videoShowing: Boolean)

    /** Undo [startAudioSession]. Safe when it never ran. */
    fun stopAudioSession()

    /** [session] has ended and released everything. Drop its notifications and let the service stop. */
    fun onSessionFinished(session: CallSession)
}
