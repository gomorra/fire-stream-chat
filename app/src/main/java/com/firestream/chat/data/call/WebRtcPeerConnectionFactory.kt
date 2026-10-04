package com.firestream.chat.data.call

import android.content.Context
import org.webrtc.AudioSource
import org.webrtc.AudioTrack
import org.webrtc.DefaultVideoDecoderFactory
import org.webrtc.DefaultVideoEncoderFactory
import org.webrtc.EglBase
import org.webrtc.MediaConstraints
import org.webrtc.PeerConnection
import org.webrtc.PeerConnectionFactory
import org.webrtc.VideoSource
import org.webrtc.VideoTrack
import java.util.concurrent.atomic.AtomicBoolean

/**
 * The WebRTC factory of one call, and the EGL context its video shares.
 *
 * Everything made here is disposed by whoever asked for it, before [dispose]. The order for a
 * whole call is: capturer, texture helper, video source, tracks, connections, then [dispose],
 * which ends with the EGL context.
 */
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

    private val eglBase: EglBase = EglBase.create()
    private val factory: PeerConnectionFactory
    private var audioSource: AudioSource? = null

    /**
     * The EGL context of this call. The encoders and decoders, the camera's texture helper and
     * every video view are built on it, so a frame stays a texture from the camera to the screen.
     */
    val eglContext: EglBase.Context get() = eglBase.eglBaseContext

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
        factory = PeerConnectionFactory.builder()
            .setVideoEncoderFactory(
                DefaultVideoEncoderFactory(
                    eglBase.eglBaseContext,
                    /* enableIntelVp8Encoder = */ true,
                    /* enableH264HighProfile = */ true
                )
            )
            .setVideoDecoderFactory(DefaultVideoDecoderFactory(eglBase.eglBaseContext))
            .createPeerConnectionFactory()
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

    /** A source for camera frames. The caller disposes it, after the capturer that feeds it. */
    fun createVideoSource(): VideoSource = factory.createVideoSource(/* isScreencast = */ false)

    /** The track of [source]. The caller disposes it, after the source. */
    fun createVideoTrack(source: VideoSource): VideoTrack = factory.createVideoTrack("video_track_0", source)

    fun dispose() {
        audioSource?.dispose()
        audioSource = null
        factory.dispose()
        eglBase.release()
    }
}
