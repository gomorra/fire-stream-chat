// region: AGENT-NOTE
// Responsibility: One WebRTC connection to one remote person — create it, negotiate the
//   offer and answer, exchange ICE candidates, report what happens to it.
// Owns: The `PeerConnection`, its SDP observers, the rule that remote candidates wait for
//   the remote description, the duplicate-candidate filter, the direct-or-relayed log line,
//   the one video line of the connection and whether both sides agreed to use it.
// Collaborators: PeerSignaling (where offer, answer and candidates travel),
//   WebRtcPeerConnectionFactory (builds the connection), WebRtcCallLocalMedia (opens and
//   closes the session and owns the local tracks), CallSession (reacts to its events and
//   owns everything about the call as a whole).
// Don't put here: Firestore paths or call status (PeerSignaling, CallSession), the
//   foreground service, notification, timers or audio session (CallSession, CallService),
//   creating or disposing local tracks (their owner shares them across sessions).
// endregion

package com.firestream.chat.data.call

import android.util.Log
import com.firestream.chat.domain.model.IceCandidateData
import com.firestream.chat.domain.model.IceServerData
import com.firestream.chat.domain.model.SdpData
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import org.webrtc.CandidatePairChangeEvent
import org.webrtc.DataChannel
import org.webrtc.IceCandidate
import org.webrtc.MediaConstraints
import org.webrtc.MediaStream
import org.webrtc.MediaStreamTrack
import org.webrtc.PeerConnection
import org.webrtc.RtpSender
import org.webrtc.RtpTransceiver
import org.webrtc.SdpObserver
import org.webrtc.SessionDescription
import org.webrtc.VideoTrack
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/** What a [PeerSession] reports to its owner, in order, through [PeerSession.events]. */
sealed interface PeerSessionEvent {
    /** ICE connected. Reported again when a disconnected session comes back. */
    data object Connected : PeerSessionEvent

    /** ICE lost the path and is trying to recover. The session is still open. */
    data object Disconnected : PeerSessionEvent

    /** The session cannot continue. Reported at most once; the owner closes the session. */
    data class Failed(val reason: String) : PeerSessionEvent

    /** The remote side's track arrived. A video track arrives whether or not the camera is on. */
    data class RemoteTrack(val track: MediaStreamTrack) : PeerSessionEvent

    /**
     * Offer and answer are both applied. [available] is true when both sides agreed to send and
     * receive on the video line. An offer without a video line makes it false. Reported once.
     */
    data class VideoLine(val available: Boolean) : PeerSessionEvent
}

/**
 * One connection to one remote person. Lifecycle is [start] → [close], each idempotent.
 *
 * Threading: WebRTC calls the observers on its signalling thread, the signalling flows are
 * collected on [scope], and [close] arrives from whichever thread ends the call. Two rules follow.
 *
 * - **Events leave through a channel.** The owner collects [events] on its own scope, so it reacts
 *   off the signalling thread. `PeerConnection.close()` waits for that thread, so [close] must
 *   never run inside a callback of this connection. Collecting [events] is what guarantees it.
 * - **No `PeerConnection` method is called while [candidateLock] is held.** Those methods hop to
 *   the signalling thread and wait, and a callback on that thread may be waiting for the lock.
 * - **[cameraLock] is never taken on the signalling thread.** `RtpSender.setTrack` runs under it
 *   and waits for that thread.
 *
 * A connection has at most one video line, set up with the offer and the answer, in both
 * directions. [setCamera] puts the camera track on it or takes it off; no new offer is needed. The
 * track goes on only after both sides agreed to send and receive there ([videoAvailable]), so the
 * camera is never sent to an app that cannot show it.
 *
 * An app without video must never be offered the line: it aborts the process when it applies such
 * an offer. The owner says through [offerVideoLine] whether the other side takes one.
 *
 * [close] disposes the connection, and not the local tracks. The tracks belong to the owner,
 * which shares them between sessions.
 *
 * @param factory builds the connection.
 * @param signaling where this pair's offer, answer and candidates travel.
 * @param localTracks the tracks to send from the start: the microphone. The session adds them and
 *   never disposes them. The camera track is not one of them; it comes through [setCamera].
 * @param offers true when this side makes the offer, false when it answers one.
 * @param offerVideoLine whether the offer carries the video line. Read only when [offers]: the
 *   side that answers takes the line the offer brings. False unless the other side is known to
 *   run an app with video.
 * @param iceServers the STUN servers and the relay the connection is built with.
 * @param scope the owner's scope. The session runs in a child of it, which [close] cancels.
 * @param logTag the tag of every line this session logs.
 */
class PeerSession(
    private val factory: WebRtcPeerConnectionFactory,
    private val signaling: PeerSignaling,
    private val localTracks: List<MediaStreamTrack>,
    private val offers: Boolean,
    private val offerVideoLine: Boolean,
    private val iceServers: List<IceServerData>,
    scope: CoroutineScope,
    private val logTag: String = "PeerSession"
) {

    private val sessionScope =
        CoroutineScope(scope.coroutineContext + SupervisorJob(scope.coroutineContext[Job]))

    private val eventChannel = Channel<PeerSessionEvent>(Channel.UNLIMITED)

    /** Every event of this session, in order. Completes when the session is closed. One collector. */
    val events: Flow<PeerSessionEvent> = eventChannel.receiveAsFlow()

    private val started = AtomicBoolean(false)
    private val closed = AtomicBoolean(false)
    private val failed = AtomicBoolean(false)

    /** Emptied by whoever closes the connection, so it is closed exactly once. */
    private val connection = AtomicReference<PeerConnection?>()

    /** Guards the three fields below. See the class note for what must not happen under it. */
    private val candidateLock = Any()
    private var remoteDescriptionSet = false
    private val heldCandidates = mutableListOf<IceCandidateData>()
    private val seenCandidates = mutableSetOf<IceCandidateData>()

    // Both only touched by the connection observer, so only on the signalling thread.
    private var connected = false
    private var path: IcePath? = null

    /**
     * The video line of this connection, or null when the offer has none. Written once: by [start]
     * when offering, on the signalling thread when answering.
     */
    @Volatile private var videoTransceiver: RtpTransceiver? = null

    /**
     * True when both sides agreed to send and receive on the video line. False until offer and
     * answer are applied, and for good when the offer has no video line.
     */
    @Volatile
    var videoAvailable: Boolean = false
        private set

    /** Guards the three fields below. See the class note for where it must not be taken. */
    private val cameraLock = Any()

    /** What the owner asked to send. */
    private var camera: VideoTrack? = null

    /** The sending end of the video line. Set only once the line is agreed. */
    private var videoSender: RtpSender? = null

    /** What [videoSender] carries now. */
    private var attached: VideoTrack? = null

    /** Create the connection and start negotiating. A second call does nothing. */
    fun start() {
        if (!started.compareAndSet(false, true) || closed.get()) return

        val pc = factory.createPeerConnection(observer, iceServers)
        if (pc == null) {
            fail("no peer connection")
            return
        }
        connection.set(pc)
        if (closed.get()) {
            // close() ran between the check above and the set, and found nothing to close.
            connection.getAndSet(null)?.dispose()
            return
        }

        try {
            localTracks.forEach { pc.addTrack(it) }
            // The side that answers takes the line the offer brings, in takeOfferedVideoLine().
            // Without the line the offer is the audio-only one an app without video expects.
            if (offers && offerVideoLine) {
                videoTransceiver = pc.addTransceiver(
                    MediaStreamTrack.MediaType.MEDIA_TYPE_VIDEO,
                    RtpTransceiver.RtpTransceiverInit(RtpTransceiver.RtpTransceiverDirection.SEND_RECV)
                )
            }
        } catch (e: IllegalStateException) {
            // The call ended on another thread while this one was starting: addTrack and
            // addTransceiver throw on a closed connection, addTrack also on a disposed track.
            // fail() reports nothing once closed.
            Log.w(logTag, "Could not add a local track", e)
            fail("local track rejected")
            return
        }

        sessionScope.launch {
            signaling.observeCandidates()
                .catch { e -> Log.e(logTag, "ICE candidate listener error", e) }
                .collect { onRemoteCandidates(it) }
        }

        if (offers) {
            createLocalDescription(pc)
            sessionScope.launch {
                signaling.observeAnswer()
                    .catch { e -> Log.e(logTag, "Answer listener error", e) }
                    .firstOrNull()
                    ?.let { answer -> applyRemoteDescription(answer) { onNegotiated() } }
            }
        } else {
            sessionScope.launch {
                val offer = try {
                    signaling.observeOffer().first()
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Log.e(logTag, "Failed to get the offer", e)
                    fail("no offer")
                    return@launch
                }
                applyRemoteDescription(offer) { pc ->
                    takeOfferedVideoLine(pc)
                    createLocalDescription(pc)
                }
            }
        }
    }

    /**
     * Send [track] on the video line, or nothing when it is null. The track goes on the line as
     * soon as [videoAvailable] is true, and at once when it already is. It never goes on a line
     * the other side did not agree to. The session does not dispose the track.
     *
     * Waits for the signalling thread, so never call it from a callback of this connection. Before
     * the owner disposes a track it passed here, it calls `setCamera(null)` or [close].
     */
    fun setCamera(track: VideoTrack?) {
        synchronized(cameraLock) {
            camera = track
            applyCamera()
        }
    }

    /**
     * Stop negotiating and release the connection. A second call does nothing. Never call this
     * from inside a callback of this connection; react to [events] instead.
     */
    fun close() {
        if (!closed.compareAndSet(false, true)) return
        sessionScope.cancel()
        eventChannel.close()
        // Waits for a setCamera that is under way. None touches the line after this.
        synchronized(cameraLock) { videoSender = null }
        // dispose(), not close(): close() leaves the native connection allocated, and with it
        // the observer that holds this session.
        connection.getAndSet(null)?.dispose()
    }

    // ──────────────────────────────────────────────────────────────────────────
    // Offer and answer
    // ──────────────────────────────────────────────────────────────────────────

    /** Create this side's description (the offer or the answer), set it, and send it. */
    private fun createLocalDescription(pc: PeerConnection) {
        val kind = if (offers) "offer" else "answer"
        val created = object : FailingSdpObserver("create $kind") {
            override fun onCreateSuccess(sdp: SessionDescription) {
                if (closed.get()) return
                pc.setLocalDescription(object : FailingSdpObserver("set local $kind") {
                    // The side that answers has both descriptions once its own answer is set.
                    override fun onSetSuccess() {
                        if (!offers) onNegotiated()
                    }
                }, sdp)
                sessionScope.launch {
                    if (offers) signaling.sendOffer(sdp.toData()) else signaling.sendAnswer(sdp.toData())
                }
            }
        }
        if (offers) {
            pc.createOffer(created, receiveAudioConstraints())
        } else {
            pc.createAnswer(created, receiveAudioConstraints())
        }
    }

    /** @param then runs on the signalling thread once the description is set. */
    private fun applyRemoteDescription(sdp: SdpData, then: (PeerConnection) -> Unit = {}) {
        val pc = connection.get() ?: return
        val description = try {
            SessionDescription(SessionDescription.Type.fromCanonicalForm(sdp.type), sdp.sdp)
        } catch (e: IllegalArgumentException) {
            fail("remote description of unknown type '${sdp.type}'")
            return
        }
        pc.setRemoteDescription(object : FailingSdpObserver("set remote ${sdp.type}") {
            override fun onSetSuccess() {
                if (closed.get()) return
                releaseHeldCandidates(pc)
                then(pc)
            }
        }, description)
    }

    /**
     * Nothing about video: the video line is the transceiver. An `OfferToReceiveVideo` of false
     * here would take the receiving half off that line.
     */
    private fun receiveAudioConstraints() = MediaConstraints().apply {
        mandatory.add(MediaConstraints.KeyValuePair("OfferToReceiveAudio", "true"))
    }

    // ──────────────────────────────────────────────────────────────────────────
    // The video line
    // ──────────────────────────────────────────────────────────────────────────

    /**
     * The side that answers takes the video line the offer brought and agrees to send on it too.
     * An offer from an older app brings none. Runs on the signalling thread, once:
     * `getTransceivers()` disposes the objects it returned the time before.
     */
    private fun takeOfferedVideoLine(pc: PeerConnection) {
        val offered = pc.transceivers
            .firstOrNull { it.mediaType == MediaStreamTrack.MediaType.MEDIA_TYPE_VIDEO }
            ?: return
        offered.setDirection(RtpTransceiver.RtpTransceiverDirection.SEND_RECV)
        videoTransceiver = offered
    }

    /** Offer and answer are both applied. Runs on the signalling thread. */
    private fun onNegotiated() {
        if (closed.get()) return
        val line = videoTransceiver
        // No line: this side offered none, or an app without video made the offer. A line the
        // answer did not take in both directions is not agreed either.
        val available = line?.currentDirection == RtpTransceiver.RtpTransceiverDirection.SEND_RECV
        videoAvailable = available
        emit(PeerSessionEvent.VideoLine(available))
        if (line != null && available) {
            val sender = line.sender
            // Off the signalling thread: applyCamera() runs under cameraLock.
            sessionScope.launch {
                synchronized(cameraLock) {
                    videoSender = sender
                    applyCamera()
                }
            }
        }
    }

    /** Caller holds [cameraLock]. Makes the line carry [camera], once the line is agreed. */
    private fun applyCamera() {
        val sender = videoSender ?: return
        if (closed.get() || attached === camera) return
        try {
            if (sender.setTrack(camera, /* takeOwnership = */ false)) {
                attached = camera
            } else {
                Log.w(logTag, "The video line did not take the camera track")
            }
        } catch (e: IllegalStateException) {
            // The call ended on another thread: the track or the connection is already gone.
            Log.w(logTag, "Could not change the camera track", e)
        }
    }

    // ──────────────────────────────────────────────────────────────────────────
    // ICE candidates
    // ──────────────────────────────────────────────────────────────────────────

    /** WebRTC rejects a remote candidate that arrives before the remote description, so hold it. */
    private fun onRemoteCandidates(candidates: List<IceCandidateData>) {
        val ready = synchronized(candidateLock) {
            val fresh = candidates.filter { seenCandidates.add(it) }
            if (remoteDescriptionSet) {
                fresh
            } else {
                heldCandidates += fresh
                emptyList()
            }
        }
        val pc = connection.get() ?: return
        ready.forEach { pc.addRemote(it) }
    }

    private fun releaseHeldCandidates(pc: PeerConnection) {
        val held = synchronized(candidateLock) {
            remoteDescriptionSet = true
            heldCandidates.toList().also { heldCandidates.clear() }
        }
        held.forEach { pc.addRemote(it) }
    }

    private fun PeerConnection.addRemote(candidate: IceCandidateData) {
        addIceCandidate(IceCandidate(candidate.sdpMid, candidate.sdpMLineIndex, candidate.sdp))
    }

    // ──────────────────────────────────────────────────────────────────────────
    // Connection callbacks — all on the WebRTC signalling thread
    // ──────────────────────────────────────────────────────────────────────────

    private val observer = object : PeerConnection.Observer {
        override fun onIceCandidate(candidate: IceCandidate) {
            if (closed.get()) return
            val data = IceCandidateData(candidate.sdpMid, candidate.sdpMLineIndex, candidate.sdp)
            sessionScope.launch { signaling.sendCandidate(data) }
        }

        override fun onIceConnectionChange(state: PeerConnection.IceConnectionState) {
            Log.d(logTag, "ICE connection state: $state")
            when (state) {
                // ICE reports CONNECTED and then COMPLETED; the owner hears of it once.
                PeerConnection.IceConnectionState.CONNECTED,
                PeerConnection.IceConnectionState.COMPLETED -> {
                    if (!connected) {
                        connected = true
                        Log.i(logTag, "Connected: ${path?.describe() ?: "path not known yet"}")
                        emit(PeerSessionEvent.Connected)
                    }
                }
                PeerConnection.IceConnectionState.DISCONNECTED -> {
                    Log.w(logTag, "ICE disconnected — may reconnect")
                    connected = false
                    emit(PeerSessionEvent.Disconnected)
                }
                PeerConnection.IceConnectionState.FAILED -> fail("ICE connection failed")
                else -> {}
            }
        }

        override fun onSelectedCandidatePairChanged(event: CandidatePairChangeEvent) {
            val selected = IcePath.of(event.local?.sdp, event.remote?.sdp, event.local?.serverUrl)
            path = selected
            // The pair can be chosen after ICE reports connected, and it can change mid-call.
            if (connected) Log.i(logTag, "Path: ${selected.describe()}")
        }

        override fun onTrack(transceiver: RtpTransceiver?) {
            val track = transceiver?.receiver?.track() ?: return
            emit(PeerSessionEvent.RemoteTrack(track))
        }

        override fun onSignalingChange(state: PeerConnection.SignalingState?) {}
        override fun onIceConnectionReceivingChange(receiving: Boolean) {}
        override fun onIceGatheringChange(state: PeerConnection.IceGatheringState?) {}
        override fun onIceCandidatesRemoved(candidates: Array<out IceCandidate>?) {}
        override fun onAddStream(stream: MediaStream?) {}
        override fun onRemoveStream(stream: MediaStream?) {}
        override fun onDataChannel(dc: DataChannel?) {}
        override fun onRenegotiationNeeded() {}
    }

    private fun emit(event: PeerSessionEvent) {
        // Fails once the channel is closed, so a closed session reports nothing new. An event
        // queued before the close is still delivered, and the owner tells by identity that the
        // session is gone.
        eventChannel.trySend(event)
    }

    private fun fail(reason: String) {
        Log.e(logTag, "Session failed: $reason")
        if (failed.compareAndSet(false, true)) emit(PeerSessionEvent.Failed(reason))
    }

    /** Turns every SDP failure into the session's one [PeerSessionEvent.Failed]. */
    private open inner class FailingSdpObserver(private val what: String) : SdpObserver {
        override fun onCreateSuccess(sdp: SessionDescription) {}
        override fun onSetSuccess() {}
        override fun onCreateFailure(error: String?) = fail("$what: $error")
        override fun onSetFailure(error: String?) = fail("$what: $error")
    }
}

private fun SessionDescription.toData() = SdpData(description, type.canonicalForm())
