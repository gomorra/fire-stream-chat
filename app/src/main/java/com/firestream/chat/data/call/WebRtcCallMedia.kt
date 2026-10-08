package com.firestream.chat.data.call

import android.content.Context
import android.os.Handler
import android.util.Log
import com.firestream.chat.domain.model.IceCandidateData
import com.firestream.chat.domain.model.SdpData
import org.webrtc.AudioTrack
import org.webrtc.DataChannel
import org.webrtc.IceCandidate
import org.webrtc.MediaConstraints
import org.webrtc.MediaStream
import org.webrtc.PeerConnection
import org.webrtc.RtpReceiver
import org.webrtc.RtpTransceiver
import org.webrtc.SdpObserver
import org.webrtc.SessionDescription

/**
 * [CallMedia] over a WebRTC peer connection, with a factory and audio module of its own.
 *
 * Created, used and disposed on the main thread. WebRTC calls the observers on its signaling
 * thread, so each callback is posted to [mainHandler], and dropped there once [dispose] has run.
 * That also keeps a callback from a released connection from reaching the next call.
 */
internal class WebRtcCallMedia(
    context: Context,
    private val mainHandler: Handler,
    private val listener: CallMedia.Listener,
) : CallMedia {

    private var disposed = false

    private val observer = object : PeerConnection.Observer {
        override fun onIceCandidate(candidate: IceCandidate) = onMain {
            listener.onLocalIceCandidate(IceCandidateData(candidate.sdpMid, candidate.sdpMLineIndex, candidate.sdp))
        }

        override fun onIceConnectionChange(state: PeerConnection.IceConnectionState) {
            Log.d(TAG, "ICE connection state: $state")
            val change = when (state) {
                PeerConnection.IceConnectionState.CONNECTED,
                PeerConnection.IceConnectionState.COMPLETED -> ConnectionChange.CONNECTED
                PeerConnection.IceConnectionState.DISCONNECTED -> ConnectionChange.DISCONNECTED
                PeerConnection.IceConnectionState.FAILED -> ConnectionChange.FAILED
                else -> return
            }
            onMain { listener.onConnectionChange(change) }
        }

        override fun onSignalingChange(state: PeerConnection.SignalingState?) {}
        override fun onIceConnectionReceivingChange(receiving: Boolean) {}
        override fun onIceGatheringChange(state: PeerConnection.IceGatheringState?) {}
        override fun onIceCandidatesRemoved(candidates: Array<out IceCandidate>?) {}
        override fun onAddStream(stream: MediaStream?) {}
        override fun onRemoveStream(stream: MediaStream?) {}
        override fun onDataChannel(dc: DataChannel?) {}
        override fun onRenegotiationNeeded() {}
        override fun onAddTrack(receiver: RtpReceiver?, streams: Array<out MediaStream>?) {}
        override fun onTrack(transceiver: RtpTransceiver?) {}
    }

    private var factory: WebRtcPeerConnectionFactory? = WebRtcPeerConnectionFactory(context)
    private var peerConnection: PeerConnection? = factory?.createPeerConnection(observer)
    private var audioTrack: AudioTrack? = factory?.createAudioTrack()?.also { peerConnection?.addTrack(it) }

    /** Run [block] on the main thread, unless this connection has been disposed by then. */
    private fun onMain(block: () -> Unit) {
        mainHandler.post { if (!disposed) block() }
    }

    override fun createLocalOffer(onCreated: (SdpData) -> Unit, onFailure: (String?) -> Unit) {
        val pc = peerConnection ?: return
        pc.createOffer(localDescriptionObserver(pc, onCreated, onFailure), audioOnly())
    }

    override fun createLocalAnswer(onCreated: (SdpData) -> Unit, onFailure: (String?) -> Unit) {
        val pc = peerConnection ?: return
        pc.createAnswer(localDescriptionObserver(pc, onCreated, onFailure), audioOnly())
    }

    private fun localDescriptionObserver(
        pc: PeerConnection,
        onCreated: (SdpData) -> Unit,
        onFailure: (String?) -> Unit,
    ) = object : SimpleSdpObserver() {
        override fun onCreateSuccess(sdp: SessionDescription) = onMain {
            pc.setLocalDescription(SimpleSdpObserver(), sdp)
            onCreated(SdpData(sdp.description, sdp.type.canonicalForm()))
        }

        override fun onCreateFailure(error: String?) = onMain { onFailure(error) }
    }

    override fun setRemoteDescription(sdp: SdpData, onSet: () -> Unit, onFailure: (String?) -> Unit) {
        val pc = peerConnection ?: return
        val description = SessionDescription(SessionDescription.Type.fromCanonicalForm(sdp.type), sdp.sdp)
        pc.setRemoteDescription(object : SimpleSdpObserver() {
            override fun onSetSuccess() = onMain(onSet)
            override fun onSetFailure(error: String?) = onMain { onFailure(error) }
        }, description)
    }

    override fun addIceCandidate(candidate: IceCandidateData) {
        peerConnection?.addIceCandidate(IceCandidate(candidate.sdpMid, candidate.sdpMLineIndex, candidate.sdp))
    }

    override fun setMicrophoneEnabled(enabled: Boolean) {
        audioTrack?.setEnabled(enabled)
    }

    override fun dispose() {
        if (disposed) return
        disposed = true

        audioTrack?.dispose()
        audioTrack = null

        // dispose(), not close(): close() leaves the native connection allocated, and with it the
        // observer that holds the call.
        peerConnection?.dispose()
        peerConnection = null

        factory?.dispose()
        factory = null
    }

    private fun audioOnly() = MediaConstraints().apply {
        mandatory.add(MediaConstraints.KeyValuePair("OfferToReceiveAudio", "true"))
        mandatory.add(MediaConstraints.KeyValuePair("OfferToReceiveVideo", "false"))
    }

    private companion object {
        const val TAG = "WebRtcCallMedia"
    }
}

/** Minimal [SdpObserver] that logs failures; override [onCreateSuccess] for results. */
private open class SimpleSdpObserver : SdpObserver {
    override fun onCreateSuccess(sdp: SessionDescription) {}
    override fun onSetSuccess() {}
    override fun onCreateFailure(error: String?) {
        Log.e("SimpleSdpObserver", "SDP create failure: $error")
    }
    override fun onSetFailure(error: String?) {
        Log.e("SimpleSdpObserver", "SDP set failure: $error")
    }
}
