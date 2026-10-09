// region: AGENT-NOTE
// Responsibility: The WebRTC objects of one call — the factory, the microphone track, the
//   camera, and a PeerSession per remote person — and the one order they are released in.
// Owns: The factory and everything made on it for the length of the call. Which track each
//   connection and CallVideoSinks carry. The worker the camera's device calls run on.
// Collaborators: CallSession (the only caller, through CallLocalMedia), PeerSession (one
//   connection), LocalCamera (the capturer), CallVideoSinks (the views),
//   WebRtcPeerConnectionFactory.
// Don't put here: Whether the camera should run (CameraSwitch), the call's states, timers or
//   signalling (CallSession), permissions or the foreground type (CallService).
// endregion

package com.firestream.chat.data.call

import android.content.Context
import android.util.Log
import com.firestream.chat.domain.model.IceServerData
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.webrtc.AudioTrack
import org.webrtc.VideoTrack

/**
 * [CallLocalMedia] over WebRTC, with a factory and an audio module of its own.
 *
 * Created, used and disposed on the main thread. Its fields are touched nowhere else. Two things
 * leave the main thread, because they wait for other threads:
 *
 * - **The camera's device calls** run one at a time on [worker]. Each result comes back to the
 *   main thread before anything is attached.
 * - **The rest of a finished call's release** runs on [worker] too: the camera, the tracks and
 *   the factory. [dispose] closes the connections itself, at once, and takes everything else
 *   out of this object, so nothing else refers to what is being released.
 *
 * WebRTC calls a [PeerSession] on its signaling thread. The session reports through its events,
 * which [CallSession] collects on the main thread, so teardown never runs on the signaling
 * thread: disposing the factory frees that very thread.
 *
 * @param appScope for the release of the call's media, which outlives the call.
 */
internal class WebRtcCallLocalMedia(
    private val context: Context,
    private val sinks: CallVideoSinks,
    private val listener: CallLocalMedia.Listener,
    private val appScope: CoroutineScope,
) : CallLocalMedia {

    /** Cancelled by [dispose], with every callback that is still on its way to the main thread. */
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var disposed = false

    private val factory = WebRtcPeerConnectionFactory(context).also { sinks.open(it.eglContext) }

    /** Made with the first connection. An incoming ring that shows a preview has no use for it. */
    private var audioTrack: AudioTrack? = null
    private var microphoneEnabled = true
    private val peers = mutableListOf<PeerSession>()

    /** Made when the camera is first switched on. */
    private var camera: LocalCamera? = null
    private var cameraWanted = false

    /** Counts the starts and stops asked of the camera, so a start's late answer knows whether it still counts. */
    private var cameraRequest = 0

    /** The camera track while the camera captures, and what every connection sends. */
    private var cameraTrack: VideoTrack? = null

    override val framed: Flow<Set<String>> get() = sinks.framed

    override fun awaitFrame(participantId: String) = sinks.awaitFrame(participantId)

    override fun openPeer(
        remoteId: String,
        signaling: PeerSignaling,
        offers: Boolean,
        offerVideoLine: Boolean,
        iceServers: List<IceServerData>,
    ): Flow<PeerSessionEvent> {
        val microphone = audioTrack ?: factory.createAudioTrack().also {
            it.setEnabled(microphoneEnabled)
            audioTrack = it
        }
        val peer = PeerSession(
            factory = factory,
            signaling = signaling,
            localTracks = listOf(microphone),
            offers = offers,
            offerVideoLine = offerVideoLine,
            iceServers = iceServers,
            scope = scope,
        )
        peers += peer
        peer.start()
        // A camera that already runs, as a preview, goes out once the video line is agreed.
        peer.setCamera(cameraTrack)
        return peer.events.onEach { event ->
            // Remote audio plays without a sink. Remote video needs the views.
            val track = (event as? PeerSessionEvent.RemoteTrack)?.track as? VideoTrack ?: return@onEach
            if (!disposed) sinks.setTrack(remoteId, track)
        }
    }

    override fun setMicrophoneEnabled(enabled: Boolean) {
        microphoneEnabled = enabled
        audioTrack?.setEnabled(enabled)
    }

    // ──────────────────────────────────────────────────────────────────────────
    // Camera
    // ──────────────────────────────────────────────────────────────────────────

    override fun setCameraRunning(running: Boolean) {
        if (disposed || cameraWanted == running) return
        cameraWanted = running
        cameraRequest++
        if (running) startCamera() else stopCamera()
    }

    private fun startCamera() {
        val camera = camera ?: newCamera().also { camera = it }
        val request = cameraRequest
        scope.launch {
            val track = withContext(worker) { camera.start() }
            // A stop or a new start came while the camera was opening. It is queued behind this
            // start, and the answer to this one is nobody's any more.
            if (request != cameraRequest) return@launch
            if (track == null) {
                // The device has no camera, or it did not open.
                cameraWanted = false
                listener.onCameraFailed()
                return@launch
            }
            cameraTrack = track
            // A device without a front camera starts on the back one.
            sinks.setLocalMirrored(camera.isFront)
            sinks.setTrack(CallVideoSinks.LOCAL, track)
            peers.forEach { it.setCamera(track) }
            listener.onCameraStarted(camera.isFront)
        }
    }

    private fun stopCamera() {
        // Off the connections first, then the capturer.
        peers.forEach { it.setCamera(null) }
        cameraTrack = null
        val camera = camera ?: return
        scope.launch {
            withContext(worker) { camera.stop() }
            // The track stays, and a view keeps the last picture it drew.
            sinks.awaitFrame(CallVideoSinks.LOCAL)
        }
    }

    override fun flipCamera() {
        val camera = camera ?: return
        scope.launch { withContext(worker) { camera.flip() } }
    }

    /** The camera's callbacks run on the camera thread, so each is sent to the main thread. */
    private fun newCamera() = LocalCamera(
        context = context,
        factory = factory,
        onFlipped = { front ->
            scope.launch {
                // The self view mirrors only the front camera.
                sinks.setLocalMirrored(front)
                listener.onCameraFlipped(front)
            }
        },
        onFailure = { reason ->
            Log.w(TAG, "The camera stopped: $reason")
            scope.launch {
                // Another app took the camera, or it broke.
                if (!cameraWanted) return@launch
                cameraWanted = false
                cameraRequest++
                stopCamera()
                listener.onCameraFailed()
            }
        },
    )

    // ──────────────────────────────────────────────────────────────────────────
    // Release
    // ──────────────────────────────────────────────────────────────────────────

    override fun dispose() {
        if (disposed) return
        disposed = true
        scope.cancel()
        // The views let go of every track before anything is disposed.
        sinks.close()
        // Each step on its own: one that fails must not keep the connection open.
        var clean = releaseStep("microphone off") { audioTrack?.setEnabled(false) }
        // The camera track comes off every connection before either is disposed.
        clean = releaseStep("camera off the connections") { peers.forEach { it.setCamera(null) } } && clean
        // Here and now: the call is silent and the microphone free before the caller hands the
        // audio session back. It also ends every connection's events.
        clean = releaseStep("connections") { peers.forEach { it.close() } } && clean
        val released = Released(camera, audioTrack, factory, clean)
        peers.clear()
        camera = null
        cameraTrack = null
        audioTrack = null
        // The camera waits for its own thread until the device is closed.
        appScope.launch(worker) { released.release() }
    }

    /**
     * What is left of a finished call once its connections are closed. Nothing else refers to it
     * any more.
     *
     * @param cleanSoFar false when a step before these failed.
     */
    private class Released(
        val camera: LocalCamera?,
        val audioTrack: AudioTrack?,
        val factory: WebRtcPeerConnectionFactory,
        val cleanSoFar: Boolean,
    ) {
        /** Dispose the rest in the one order that is safe: capturer, texture helper, video source, tracks, factory, EGL context. */
        fun release() {
            var clean = releaseStep("camera") { camera?.dispose() } && cleanSoFar
            clean = releaseStep("microphone") { audioTrack?.dispose() } && clean
            // The factory goes only when everything made on it is gone. Otherwise it leaks,
            // which is better than a native crash after the hang-up.
            if (clean) releaseStep("factory") { factory.dispose() }
        }
    }

    private companion object {
        const val TAG = "CallLocalMedia"

        /**
         * One at a time and off the main thread: the camera's device calls, which wait for the
         * camera thread, and the release of a call's media. One for the whole process, so the
         * next call opens the camera only after the call before has closed it.
         */
        val worker = Dispatchers.IO.limitedParallelism(1)

        /** @return false when [block] threw. The call is over either way, so the failure is only logged. */
        inline fun releaseStep(what: String, block: () -> Unit): Boolean = try {
            block()
            true
        } catch (e: RuntimeException) {
            Log.e(TAG, "Releasing the call's media failed at: $what", e)
            false
        }
    }
}
