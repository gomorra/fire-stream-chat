package com.firestream.chat.data.call

import android.content.Context
import org.webrtc.AudioSource
import org.webrtc.AudioTrack
import org.webrtc.MediaConstraints
import org.webrtc.PeerConnection
import org.webrtc.PeerConnectionFactory
import java.util.concurrent.atomic.AtomicBoolean

class WebRtcPeerConnectionFactory(context: Context) {

    companion object {
        private val initialized = AtomicBoolean(false)

        private const val TURN_HOST = "openrelay.metered.ca"
        private const val TURN_USERNAME = "openrelayproject"
        private const val TURN_PASSWORD = "openrelayproject"

        fun initializeOnce(context: Context) {
            if (initialized.compareAndSet(false, true)) {
                PeerConnectionFactory.initialize(
                    PeerConnectionFactory.InitializationOptions.builder(context.applicationContext)
                        .setEnableInternalTracer(false)
                        .createInitializationOptions()
                )
            }
        }

        private fun buildTurnServer(url: String): PeerConnection.IceServer =
            PeerConnection.IceServer.builder(url)
                .setUsername(TURN_USERNAME)
                .setPassword(TURN_PASSWORD)
                .createIceServer()
    }

    private val factory: PeerConnectionFactory
    private var audioSource: AudioSource? = null

    private val iceServers = listOf(
        PeerConnection.IceServer.builder("stun:stun.l.google.com:19302").createIceServer(),
        PeerConnection.IceServer.builder("stun:stun1.l.google.com:19302").createIceServer(),
        // TURN servers for NAT traversal (required for phone↔emulator and symmetric NAT)
        buildTurnServer("turn:$TURN_HOST:80"),
        buildTurnServer("turn:$TURN_HOST:443"),
        buildTurnServer("turn:$TURN_HOST:443?transport=tcp"),
    )

    init {
        initializeOnce(context)
        factory = PeerConnectionFactory.builder().createPeerConnectionFactory()
    }

    fun createPeerConnection(observer: PeerConnection.Observer): PeerConnection? {
        val rtcConfig = PeerConnection.RTCConfiguration(iceServers).apply {
            sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN
            continualGatheringPolicy = PeerConnection.ContinualGatheringPolicy.GATHER_CONTINUALLY
        }
        return factory.createPeerConnection(rtcConfig, observer)
    }

    fun createAudioTrack(): AudioTrack {
        val audioConstraints = MediaConstraints().apply {
            mandatory.add(MediaConstraints.KeyValuePair("googEchoCancellation", "true"))
            mandatory.add(MediaConstraints.KeyValuePair("googNoiseSuppression", "true"))
            mandatory.add(MediaConstraints.KeyValuePair("googAutoGainControl", "true"))
        }
        audioSource = factory.createAudioSource(audioConstraints)
        return factory.createAudioTrack("audio_track_0", audioSource!!)
    }

    fun dispose() {
        audioSource?.dispose()
        audioSource = null
        factory.dispose()
    }
}
