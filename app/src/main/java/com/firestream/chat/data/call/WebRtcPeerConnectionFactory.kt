package com.firestream.chat.data.call

import android.content.Context
import com.firestream.chat.domain.model.IceServerData
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

        fun initializeOnce(context: Context) {
            if (initialized.compareAndSet(false, true)) {
                PeerConnectionFactory.initialize(
                    PeerConnectionFactory.InitializationOptions.builder(context.applicationContext)
                        .setEnableInternalTracer(false)
                        .createInitializationOptions()
                )
            }
        }

        private fun IceServerData.toIceServer(): PeerConnection.IceServer =
            PeerConnection.IceServer.builder(urls)
                .apply {
                    username?.let { setUsername(it) }
                    credential?.let { setPassword(it) }
                }
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

    /**
     * @param iceServers the STUN servers and the relay of this connection, from
     *   [IceServerProvider]. A relay is what connects two phones that have no direct path, such
     *   as a phone and the emulator, or a phone behind a symmetric NAT.
     */
    fun createPeerConnection(
        observer: PeerConnection.Observer,
        iceServers: List<IceServerData>
    ): PeerConnection? {
        val rtcConfig = PeerConnection.RTCConfiguration(iceServers.map { it.toIceServer() }).apply {
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
