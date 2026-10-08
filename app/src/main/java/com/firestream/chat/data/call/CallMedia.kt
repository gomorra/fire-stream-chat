package com.firestream.chat.data.call

import com.firestream.chat.domain.model.IceCandidateData
import com.firestream.chat.domain.model.SdpData

/**
 * The connection of one call: WebRTC's peer connection and the microphone track it sends.
 * [CallService] builds one per call through [CallHost.createMedia].
 *
 * Every callback, the [Listener]'s included, arrives on the main thread, and none arrives after
 * [dispose].
 */
internal interface CallMedia {
    /** Create an offer, apply it as the local description, and hand it to [onCreated]. */
    fun createLocalOffer(onCreated: (SdpData) -> Unit, onFailure: (String?) -> Unit)

    /** Create an answer to the applied offer, apply it as the local description, and hand it to [onCreated]. */
    fun createLocalAnswer(onCreated: (SdpData) -> Unit, onFailure: (String?) -> Unit)

    /** Apply the other phone's offer or answer. Its candidates may be added only after [onSet]. */
    fun setRemoteDescription(sdp: SdpData, onSet: () -> Unit, onFailure: (String?) -> Unit)

    fun addIceCandidate(candidate: IceCandidateData)

    fun setMicrophoneEnabled(enabled: Boolean)

    /** Release the connection, the track and the factory. Idempotent. Never call it on WebRTC's signaling thread. */
    fun dispose()

    interface Listener {
        /** A candidate of this phone's, to send to the other phone. */
        fun onLocalIceCandidate(candidate: IceCandidateData)

        fun onConnectionChange(change: ConnectionChange)
    }
}

/** The changes in a call's ICE connection that the call acts on. */
internal enum class ConnectionChange {
    /** Connected or completed. ICE reports it again after every reconnect. */
    CONNECTED,

    /** Lost for now. ICE may reconnect. */
    DISCONNECTED,

    /** Lost for good. */
    FAILED,
}
