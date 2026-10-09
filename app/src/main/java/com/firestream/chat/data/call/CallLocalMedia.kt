package com.firestream.chat.data.call

import com.firestream.chat.domain.model.IceServerData
import kotlinx.coroutines.flow.Flow

/**
 * The WebRTC side of one call: its factory, its microphone and its camera, and a connection to
 * each remote person. [CallService] builds one per call through [CallHost.createLocalMedia], and
 * [WebRtcCallLocalMedia] is the real one.
 *
 * One microphone track and one camera track go into every connection. The video of both sides
 * reaches the screens through [CallVideoSinks], which this feeds for the length of the call.
 *
 * Every function is called on the main thread. Every callback arrives there, the [Listener]'s and
 * the events of [openPeer] included, and none arrives after [dispose].
 */
internal interface CallLocalMedia {
    /**
     * Open the connection to [remoteId] and start negotiating over [signaling]. It sends the
     * microphone from the start, and the camera while that runs and the video line is agreed.
     *
     * @param offers true when this side makes the offer, false when it answers one.
     * @param offerVideoLine whether the offer carries a video line. See [PeerSession].
     * @return what happens to the connection, as [PeerSession.events]. One collector.
     */
    fun openPeer(
        remoteId: String,
        signaling: PeerSignaling,
        offers: Boolean,
        offerVideoLine: Boolean,
        iceServers: List<IceServerData>,
    ): Flow<PeerSessionEvent>

    fun setMicrophoneEnabled(enabled: Boolean)

    /**
     * Start or stop the camera. Opening it takes a moment, and [Listener.onCameraStarted] or
     * [Listener.onCameraFailed] says how it went. A stop is not answered.
     */
    fun setCameraRunning(running: Boolean)

    /** Switch between the front and the back camera. Does nothing while the camera is stopped. */
    fun flipCamera()

    /** The participants whose video has delivered a frame, as [CallVideoSinks.framed]. */
    val framed: Flow<Set<String>>

    /** Forget that [participantId] delivered a frame and report the next one, as [CallVideoSinks.awaitFrame]. */
    fun awaitFrame(participantId: String)

    /**
     * Release the connections, the camera, the tracks and the factory. When this returns the
     * connections are closed, so the call is silent and the microphone is free. Idempotent. Never
     * call it on WebRTC's signaling thread.
     */
    fun dispose()

    interface Listener {
        /** The camera captures. [front] is true for the front camera. */
        fun onCameraStarted(front: Boolean)

        /** The camera did not open, or stopped on its own: it failed, or another app took it. */
        fun onCameraFailed()

        /** The camera in use changed after [flipCamera]. */
        fun onCameraFlipped(front: Boolean)
    }
}
