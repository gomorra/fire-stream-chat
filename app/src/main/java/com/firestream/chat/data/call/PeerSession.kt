// region: AGENT-NOTE
// Responsibility: One WebRTC connection to one remote person — create it, negotiate the
//   offer and answer, exchange ICE candidates, report what happens to it.
// Owns: The `PeerConnection`, its SDP observers, the rule that remote candidates wait for
//   the remote description, the duplicate-candidate filter, the direct-or-relayed log line.
// Collaborators: PeerSignaling (where offer, answer and candidates travel),
//   WebRtcPeerConnectionFactory (builds the connection), CallService (owns the session,
//   the local tracks and everything about the call as a whole).
// Don't put here: Firestore paths or call status (PeerSignaling, CallService), the
//   foreground service, notification, ring timeout or audio session (CallService),
//   creating or disposing local tracks (their owner shares them across sessions).
// endregion

package com.firestream.chat.data.call

import android.util.Log
import com.firestream.chat.domain.model.IceCandidateData
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
import org.webrtc.RtpTransceiver
import org.webrtc.SdpObserver
import org.webrtc.SessionDescription
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

    /** The remote side's track arrived. */
    data class RemoteTrack(val track: MediaStreamTrack) : PeerSessionEvent
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
 *
 * [close] closes the connection but does not dispose it or the local tracks. The tracks belong to
 * the owner, which shares them between sessions.
 *
 * @param factory builds the connection.
 * @param signaling where this pair's offer, answer and candidates travel.
 * @param localTracks the tracks to send. The session adds them and never disposes them.
 * @param offers true when this side makes the offer, false when it answers one.
 * @param scope the owner's scope. The session runs in a child of it, which [close] cancels.
 * @param logTag the tag of every line this session logs.
 */
class PeerSession(
    private val factory: WebRtcPeerConnectionFactory,
    private val signaling: PeerSignaling,
    private val localTracks: List<MediaStreamTrack>,
    private val offers: Boolean,
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

    /** Create the connection and start negotiating. A second call does nothing. */
    fun start() {
        if (!started.compareAndSet(false, true) || closed.get()) return

        val pc = factory.createPeerConnection(observer)
        if (pc == null) {
            fail("no peer connection")
            return
        }
        connection.set(pc)
        if (closed.get()) {
            // close() ran between the check above and the set, and found nothing to close.
            connection.getAndSet(null)?.close()
            return
        }

        try {
            localTracks.forEach { pc.addTrack(it) }
        } catch (e: IllegalStateException) {
            // The call ended on another thread while this one was starting: addTrack throws on
            // a closed connection and on a disposed track. fail() reports nothing once closed.
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
                    ?.let { applyRemoteDescription(it) }
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
                applyRemoteDescription(offer) { createLocalDescription(it) }
            }
        }
    }

    /**
     * Stop negotiating and close the connection. A second call does nothing. Never call this from
     * inside a callback of this connection; react to [events] instead.
     */
    fun close() {
        if (!closed.compareAndSet(false, true)) return
        sessionScope.cancel()
        eventChannel.close()
        connection.getAndSet(null)?.close()
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
                pc.setLocalDescription(FailingSdpObserver("set local $kind"), sdp)
                sessionScope.launch {
                    if (offers) signaling.sendOffer(sdp.toData()) else signaling.sendAnswer(sdp.toData())
                }
            }
        }
        if (offers) {
            pc.createOffer(created, audioOnlyConstraints())
        } else {
            pc.createAnswer(created, audioOnlyConstraints())
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

    private fun audioOnlyConstraints() = MediaConstraints().apply {
        mandatory.add(MediaConstraints.KeyValuePair("OfferToReceiveAudio", "true"))
        mandatory.add(MediaConstraints.KeyValuePair("OfferToReceiveVideo", "false"))
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
            val selected = IcePath.of(event.local?.sdp, event.remote?.sdp)
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
