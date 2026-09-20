package com.firestream.chat.data.call

import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
import com.firestream.chat.data.util.ProfileImageManager
import com.firestream.chat.data.util.parseCallAudioRoute
import com.firestream.chat.domain.model.CallAudioRoute
import com.firestream.chat.domain.model.CallState
import com.firestream.chat.domain.model.EndReason
import com.firestream.chat.domain.model.IceCandidateData
import com.firestream.chat.domain.model.SdpData
import com.firestream.chat.domain.repository.CallRepository
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
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
import javax.inject.Inject

@AndroidEntryPoint
class CallService : Service() {

    companion object {
        const val ACTION_START_OUTGOING = "com.firestream.chat.call.START_OUTGOING"
        const val ACTION_START_INCOMING = "com.firestream.chat.call.START_INCOMING"
        const val ACTION_ANSWER = "com.firestream.chat.call.ANSWER"
        const val ACTION_DECLINE = "com.firestream.chat.call.DECLINE"
        const val ACTION_HANGUP = "com.firestream.chat.call.HANGUP"
        const val ACTION_TOGGLE_MUTE = "com.firestream.chat.call.TOGGLE_MUTE"
        const val ACTION_SELECT_AUDIO_ROUTE = "com.firestream.chat.call.SELECT_AUDIO_ROUTE"

        const val EXTRA_CALL_ID = "call_id"
        const val EXTRA_CHAT_ID = "chat_id"
        const val EXTRA_REMOTE_USER_ID = "remote_user_id"
        const val EXTRA_REMOTE_NAME = "remote_name"
        const val EXTRA_REMOTE_AVATAR_URL = "remote_avatar_url"
        const val EXTRA_AUDIO_ROUTE = "audio_route"

        private const val TAG = "CallService"
        private const val RING_TIMEOUT_MS = 30_000L

        fun startOutgoing(
            context: Context,
            callId: String,
            chatId: String,
            remoteUserId: String,
            remoteName: String,
            remoteAvatarUrl: String?
        ) {
            val intent = Intent(context, CallService::class.java).apply {
                action = ACTION_START_OUTGOING
                putExtra(EXTRA_CALL_ID, callId)
                putExtra(EXTRA_CHAT_ID, chatId)
                putExtra(EXTRA_REMOTE_USER_ID, remoteUserId)
                putExtra(EXTRA_REMOTE_NAME, remoteName)
                putExtra(EXTRA_REMOTE_AVATAR_URL, remoteAvatarUrl)
            }
            context.startForegroundService(intent)
        }

        fun startIncoming(
            context: Context,
            callId: String,
            remoteUserId: String,
            remoteName: String,
            remoteAvatarUrl: String?
        ) {
            val intent = Intent(context, CallService::class.java).apply {
                action = ACTION_START_INCOMING
                putExtra(EXTRA_CALL_ID, callId)
                putExtra(EXTRA_REMOTE_USER_ID, remoteUserId)
                putExtra(EXTRA_REMOTE_NAME, remoteName)
                putExtra(EXTRA_REMOTE_AVATAR_URL, remoteAvatarUrl)
            }
            context.startForegroundService(intent)
        }

        fun sendAction(context: Context, action: String, extras: Intent.() -> Unit = {}) {
            val intent = Intent(context, CallService::class.java).apply {
                this.action = action
                extras()
            }
            context.startService(intent)
        }

        fun selectAudioRoute(context: Context, route: CallAudioRoute) =
            sendAction(context, ACTION_SELECT_AUDIO_ROUTE) { putExtra(EXTRA_AUDIO_ROUTE, route.name) }
    }

    @Inject lateinit var callRepository: CallRepository
    @Inject lateinit var callStateHolder: CallStateHolder
    @Inject lateinit var profileImageManager: ProfileImageManager

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private var webRtcFactory: WebRtcPeerConnectionFactory? = null
    private var peerConnection: PeerConnection? = null
    private var localAudioTrack: AudioTrack? = null
    private var notificationManager: CallNotificationManager? = null

    private var currentCallId: String? = null
    private var currentChatId: String? = null
    private var remoteUserId: String? = null
    private var remoteName: String? = null
    private var remoteAvatarUrl: String? = null
    private var isCaller: Boolean = false
    private var callConnectedAt: Long? = null
    private var callMessageWritten: Boolean = false

    private var ringTimeoutJob: Job? = null
    private var signalingJob: Job? = null
    private var iceCandidateJob: Job? = null

    private var audioManager: AudioManager? = null
    private var audioFocusRequest: AudioFocusRequest? = null
    private var proximityWakeLock: PowerManager.WakeLock? = null

    private val audioRouter by lazy { CallAudioRouter(getSystemService(AudioManager::class.java), mainExecutor) }

    /**
     * Guards the whole audio session — [audioFocusRequest], [previousAudioMode], [audioRouteJob],
     * [proximityWakeLock] — because it is claimed on WebRTC's signalling thread and released from
     * `serviceScope`. Held by [requestAudioFocus], [abandonAudioFocus] and [onAudioRouteChanged];
     * everything they call is private and assumes it is already held.
     */
    private val audioLock = Any()

    /**
     * Non-null exactly while routing is live, which is also what tells a route emission still in
     * flight that the call is over.
     */
    private var audioRouteJob: Job? = null

    private var previousAudioMode: Int = AudioManager.MODE_NORMAL

    // Track ICE candidates we've already processed to avoid duplicates
    private val processedIceCandidates = mutableSetOf<String>()

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        audioManager = getSystemService(AudioManager::class.java)
        notificationManager = CallNotificationManager(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START_OUTGOING -> {
                val callId = intent.getStringExtra(EXTRA_CALL_ID) ?: return stopAndReturn()
                val chatId = intent.getStringExtra(EXTRA_CHAT_ID) ?: return stopAndReturn()
                val userId = intent.getStringExtra(EXTRA_REMOTE_USER_ID) ?: return stopAndReturn()
                val name = intent.getStringExtra(EXTRA_REMOTE_NAME) ?: "Unknown"
                val avatar = intent.getStringExtra(EXTRA_REMOTE_AVATAR_URL)
                startOutgoingCall(callId, chatId, userId, name, avatar)
            }
            ACTION_START_INCOMING -> {
                val callId = intent.getStringExtra(EXTRA_CALL_ID) ?: return stopAndReturn()
                val userId = intent.getStringExtra(EXTRA_REMOTE_USER_ID) ?: return stopAndReturn()
                val name = intent.getStringExtra(EXTRA_REMOTE_NAME) ?: "Unknown"
                val avatar = intent.getStringExtra(EXTRA_REMOTE_AVATAR_URL)
                startIncomingCall(callId, userId, name, avatar)
            }
            ACTION_ANSWER -> answerIncomingCall()
            ACTION_DECLINE -> declineIncomingCall()
            ACTION_HANGUP -> hangup()
            ACTION_TOGGLE_MUTE -> toggleMute()
            ACTION_SELECT_AUDIO_ROUTE -> applySelectedRoute(intent.getStringExtra(EXTRA_AUDIO_ROUTE))
        }
        return START_NOT_STICKY
    }

    // ──────────────────────────────────────────────────────────────────────────
    // Outgoing Call Flow
    // ──────────────────────────────────────────────────────────────────────────

    private fun startOutgoingCall(callId: String, chatId: String, userId: String, name: String, avatar: String?) {
        currentCallId = callId
        currentChatId = chatId
        remoteUserId = userId
        remoteName = name
        remoteAvatarUrl = avatar
        isCaller = true
        callMessageWritten = false

        callStateHolder.updateState(
            CallState.OutgoingRinging(callId, userId, name, avatar, localAvatarPathFor(userId))
        )

        val notification = notificationManager!!.buildOutgoingCallNotification(name)
        startForeground(
            CallNotificationManager.NOTIFICATION_ID_ONGOING,
            notification,
            ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
        )

        initWebRtc()
        createOfferAndSend(callId)
        observeCallDocument(callId)
        startRingTimeout()
    }

    private fun createOfferAndSend(callId: String) {
        val pc = peerConnection ?: return

        val constraints = MediaConstraints().apply {
            mandatory.add(MediaConstraints.KeyValuePair("OfferToReceiveAudio", "true"))
            mandatory.add(MediaConstraints.KeyValuePair("OfferToReceiveVideo", "false"))
        }

        pc.createOffer(object : SimpleSdpObserver() {
            override fun onCreateSuccess(sdp: SessionDescription) {
                pc.setLocalDescription(SimpleSdpObserver(), sdp)
                serviceScope.launch {
                    callRepository.sendOffer(callId, SdpData(sdp.description, sdp.type.canonicalForm()))
                }
            }

            override fun onCreateFailure(error: String?) {
                Log.e(TAG, "Failed to create offer: $error")
                endCallWithReason(EndReason.ERROR)
            }
        }, constraints)
    }

    // ──────────────────────────────────────────────────────────────────────────
    // Incoming Call Flow
    // ──────────────────────────────────────────────────────────────────────────

    private fun startIncomingCall(callId: String, userId: String, name: String, avatar: String?) {
        currentCallId = callId
        remoteUserId = userId
        remoteName = name
        remoteAvatarUrl = avatar
        isCaller = false

        callStateHolder.updateState(
            CallState.IncomingRinging(callId, userId, name, avatar, localAvatarPathFor(userId))
        )

        val notification = notificationManager!!.buildIncomingCallNotification(name)
        // API 34+ enforces RECORD_AUDIO at startForeground() for MICROPHONE type;
        // use SHORT_SERVICE during ringing since the mic isn't needed yet.
        // Pre-34 doesn't enforce this, and SHORT_SERVICE doesn't exist, so MICROPHONE is safe.
        val serviceType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_SHORT_SERVICE
        } else {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
        }
        startForeground(CallNotificationManager.NOTIFICATION_ID_ONGOING, notification, serviceType)

        observeCallDocument(callId)
        startRingTimeout()
    }

    private fun answerIncomingCall() {
        val callId = currentCallId ?: return

        ringTimeoutJob?.cancel()

        callStateHolder.updateState(
            CallState.Connecting(callId, remoteUserId ?: "", remoteName ?: "", remoteAvatarUrl, localAvatarPathFor(remoteUserId))
        )

        val notification = notificationManager!!.buildOngoingCallNotification(remoteName ?: "Unknown")
        // Android 14+ prohibits changing from SHORT_SERVICE to another type directly;
        // exit foreground first, then re-enter as MICROPHONE now that RECORD_AUDIO is granted.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            stopForeground(STOP_FOREGROUND_DETACH)
        }
        startForeground(
            CallNotificationManager.NOTIFICATION_ID_ONGOING,
            notification,
            ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
        )

        initWebRtc()

        // Fetch the call document to get the offer, then set remote desc and create answer.
        // IMPORTANT: We must wait for setRemoteDescription to complete before creating the
        // answer or observing ICE candidates — WebRTC requires it.
        serviceScope.launch {
            callRepository.getCallById(callId).onSuccess { signalingData ->
                val offer = signalingData.offer ?: run {
                    Log.e(TAG, "No offer found in call document")
                    endCallWithReason(EndReason.ERROR)
                    return@onSuccess
                }

                val remoteDesc = SessionDescription(
                    SessionDescription.Type.fromCanonicalForm(offer.type),
                    offer.sdp
                )
                val pc = peerConnection ?: return@onSuccess
                pc.setRemoteDescription(object : SimpleSdpObserver() {
                    override fun onSetSuccess() {
                        createAnswerAndSend(callId)
                        observeIceCandidates(callId, "callerCandidates")
                    }

                    override fun onSetFailure(error: String?) {
                        Log.e(TAG, "Failed to set remote description (callee): $error")
                        endCallWithReason(EndReason.ERROR)
                    }
                }, remoteDesc)
            }.onFailure { e ->
                Log.e(TAG, "Failed to get call document", e)
                endCallWithReason(EndReason.ERROR)
            }
        }
    }

    private fun createAnswerAndSend(callId: String) {
        val pc = peerConnection ?: return

        val constraints = MediaConstraints().apply {
            mandatory.add(MediaConstraints.KeyValuePair("OfferToReceiveAudio", "true"))
            mandatory.add(MediaConstraints.KeyValuePair("OfferToReceiveVideo", "false"))
        }

        pc.createAnswer(object : SimpleSdpObserver() {
            override fun onCreateSuccess(sdp: SessionDescription) {
                pc.setLocalDescription(SimpleSdpObserver(), sdp)
                serviceScope.launch {
                    // Write answer SDP + status="answered" atomically so the caller
                    // always sees the SDP when it observes the "answered" status.
                    callRepository.sendAnswerAndAccept(callId, SdpData(sdp.description, sdp.type.canonicalForm()))
                }
            }

            override fun onCreateFailure(error: String?) {
                Log.e(TAG, "Failed to create answer: $error")
                endCallWithReason(EndReason.ERROR)
            }
        }, constraints)
    }

    private fun declineIncomingCall() {
        val callId = currentCallId ?: return
        ringTimeoutJob?.cancel()
        serviceScope.launch {
            callRepository.declineCall(callId)
        }
        callStateHolder.updateState(CallState.Ended(callId, EndReason.DECLINED))
        cleanup()
    }

    // ──────────────────────────────────────────────────────────────────────────
    // Signaling Observation
    // ──────────────────────────────────────────────────────────────────────────

    private fun observeCallDocument(callId: String) {
        signalingJob?.cancel()
        signalingJob = serviceScope.launch {
            callRepository.observeCallDocument(callId)
                .catch { e -> Log.e(TAG, "Signaling listener error", e) }
                .collectLatest { data ->
                    when (data.status) {
                        "answered" -> {
                            if (isCaller) {
                                onCallAnswered(data)
                            }
                        }
                        "declined" -> {
                            if (isCaller) {
                                writeCallMessageIfCaller(EndReason.DECLINED)
                                callStateHolder.updateState(CallState.Ended(callId, EndReason.DECLINED))
                                cleanup()
                            }
                        }
                        "ended" -> {
                            if (isCaller) writeCallMessageIfCaller(EndReason.REMOTE_HANGUP)
                            callStateHolder.updateState(CallState.Ended(callId, EndReason.REMOTE_HANGUP))
                            cleanup()
                        }
                    }
                }
        }
    }

    private fun onCallAnswered(data: com.firestream.chat.domain.model.CallSignalingData) {
        val callId = data.callId
        ringTimeoutJob?.cancel()

        callStateHolder.updateState(
            CallState.Connecting(callId, remoteUserId ?: "", remoteName ?: "", remoteAvatarUrl, localAvatarPathFor(remoteUserId))
        )

        val notification = notificationManager!!.buildOngoingCallNotification(remoteName ?: "Unknown")
        notificationManager!!.updateNotification(notification, CallNotificationManager.NOTIFICATION_ID_ONGOING)

        // Set remote description from answer — must complete before adding ICE candidates
        val answer = data.answer
        if (answer == null) {
            Log.e(TAG, "Call answered but no answer SDP found — waiting for next snapshot")
            return
        }

        val remoteDesc = SessionDescription(
            SessionDescription.Type.fromCanonicalForm(answer.type),
            answer.sdp
        )
        peerConnection?.setRemoteDescription(object : SimpleSdpObserver() {
            override fun onSetSuccess() {
                // Only start observing ICE candidates after remote description is set
                observeIceCandidates(callId, "calleeCandidates")
            }

            override fun onSetFailure(error: String?) {
                Log.e(TAG, "Failed to set remote description (caller): $error")
                endCallWithReason(EndReason.ERROR)
            }
        }, remoteDesc)
    }

    private fun observeIceCandidates(callId: String, subcollection: String) {
        iceCandidateJob?.cancel()
        iceCandidateJob = serviceScope.launch {
            callRepository.observeIceCandidates(callId, subcollection)
                .catch { e -> Log.e(TAG, "ICE candidate listener error", e) }
                .collectLatest { candidates ->
                    for (candidate in candidates) {
                        val key = "${candidate.sdpMid}:${candidate.sdpMLineIndex}:${candidate.sdp}"
                        if (processedIceCandidates.add(key)) {
                            val iceCandidate = IceCandidate(
                                candidate.sdpMid,
                                candidate.sdpMLineIndex,
                                candidate.sdp
                            )
                            peerConnection?.addIceCandidate(iceCandidate)
                        }
                    }
                }
        }
    }

    // ──────────────────────────────────────────────────────────────────────────
    // WebRTC Setup
    // ──────────────────────────────────────────────────────────────────────────

    private fun initWebRtc() {
        if (webRtcFactory != null) return

        webRtcFactory = WebRtcPeerConnectionFactory(applicationContext)
        peerConnection = webRtcFactory!!.createPeerConnection(peerConnectionObserver)

        localAudioTrack = webRtcFactory!!.createAudioTrack()
        peerConnection?.addTrack(localAudioTrack)
    }

    private val peerConnectionObserver = object : PeerConnection.Observer {
        override fun onIceCandidate(candidate: IceCandidate) {
            val callId = currentCallId ?: return
            serviceScope.launch {
                callRepository.sendIceCandidate(
                    callId,
                    isCaller,
                    IceCandidateData(candidate.sdpMid, candidate.sdpMLineIndex, candidate.sdp)
                )
            }
        }

        override fun onIceConnectionChange(state: PeerConnection.IceConnectionState) {
            Log.d(TAG, "ICE connection state: $state")
            val callId = currentCallId ?: return
            when (state) {
                PeerConnection.IceConnectionState.CONNECTED,
                PeerConnection.IceConnectionState.COMPLETED -> {
                    ringTimeoutJob?.cancel()
                    if (callConnectedAt == null) callConnectedAt = System.currentTimeMillis()
                    // The proximity wake lock is the route collector's, not this callback's.
                    requestAudioFocus()
                    callStateHolder.updateState(
                        CallState.Connected(
                            callId,
                            remoteUserId ?: "",
                            remoteName ?: "",
                            remoteAvatarUrl,
                            System.currentTimeMillis(),
                            localAvatarPathFor(remoteUserId)
                        )
                    )
                }
                PeerConnection.IceConnectionState.DISCONNECTED -> {
                    Log.w(TAG, "ICE disconnected — may reconnect")
                }
                PeerConnection.IceConnectionState.FAILED -> {
                    Log.e(TAG, "ICE connection failed")
                    endCallWithReason(EndReason.ERROR)
                }
                else -> {}
            }
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

    // ──────────────────────────────────────────────────────────────────────────
    // Call Controls
    // ──────────────────────────────────────────────────────────────────────────

    private fun hangup() {
        val callId = currentCallId ?: run { cleanup(); return }
        serviceScope.launch {
            callRepository.endCall(callId, EndReason.HANGUP.name.lowercase())
        }
        writeCallMessageIfCaller(EndReason.HANGUP)
        callStateHolder.updateState(CallState.Ended(callId, EndReason.HANGUP))
        cleanup()
    }

    private fun toggleMute() {
        callStateHolder.toggleMute()
        localAudioTrack?.setEnabled(!callStateHolder.uiControls.value.isMuted)
    }

    /** Applies a route the companion's [selectAudioRoute] sent as an intent extra. */
    private fun applySelectedRoute(routeName: String?) {
        val route = routeName?.let { parseCallAudioRoute(it) } ?: return
        audioRouter.select(route)
    }

    // ──────────────────────────────────────────────────────────────────────────
    // Timeout
    // ──────────────────────────────────────────────────────────────────────────

    private fun startRingTimeout() {
        ringTimeoutJob?.cancel()
        ringTimeoutJob = serviceScope.launch {
            delay(RING_TIMEOUT_MS)
            val callId = currentCallId ?: return@launch
            callRepository.endCall(callId, EndReason.TIMEOUT.name.lowercase())
            writeCallMessageIfCaller(EndReason.TIMEOUT)
            callStateHolder.updateState(CallState.Ended(callId, EndReason.TIMEOUT))
            cleanup()
        }
    }

    // ──────────────────────────────────────────────────────────────────────────
    // Audio Focus & Proximity
    // ──────────────────────────────────────────────────────────────────────────

    /**
     * Claims the call's audio: mode, focus, routing. Synchronized with [abandonAudioFocus] because
     * the two race — this runs on WebRTC's signalling thread (an ICE state change) while a remote
     * hangup tears the call down from `serviceScope`, and a teardown interleaved with the claim
     * would leave the phone in `MODE_IN_COMMUNICATION` with a router nobody stops.
     */
    private fun requestAudioFocus() {
        synchronized(audioLock) {
            val am = audioManager ?: return
            // ICE reports CONNECTED and then COMPLETED, both of which land here. Without this guard
            // the second pass saves MODE_IN_COMMUNICATION as the mode to restore and the phone never
            // leaves communication mode after the call.
            if (audioFocusRequest != null) return
            previousAudioMode = am.mode

            am.mode = AudioManager.MODE_IN_COMMUNICATION

            audioFocusRequest = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .build()
                )
                .build()
            am.requestAudioFocus(audioFocusRequest!!)

            startRouting()
        }
    }

    private fun abandonAudioFocus() {
        synchronized(audioLock) {
            stopRouting()
            val am = audioManager ?: return
            audioFocusRequest?.let { am.abandonAudioFocusRequest(it) }
            am.mode = previousAudioMode
            audioFocusRequest = null
        }
    }

    /**
     * Routing starts only once the mode is `MODE_IN_COMMUNICATION` — the device list is empty
     * before. Reached once per call: its caller has already returned if the session is claimed.
     */
    private fun startRouting() {
        audioRouter.start()
        audioRouteJob = serviceScope.launch {
            audioRouter.state.collect { onAudioRouteChanged(it) }
        }
    }

    /**
     * Runs under [audioLock], as does [onAudioRouteChanged], so an emission already in flight
     * cannot re-acquire the wake lock behind the cleanup's back — cancellation is cooperative and
     * does not stop a collector that is already inside the body.
     */
    private fun stopRouting() {
        audioRouteJob?.cancel()
        audioRouteJob = null
        audioRouter.stop()
        releaseProximityWakeLock()
    }

    private fun onAudioRouteChanged(state: CallRouteState) {
        synchronized(audioLock) {
            if (audioRouteJob == null) return
            callStateHolder.updateAudioRoutes(state.available, state.current)
        // The proximity sensor follows the route the OS is actually on: blanking the screen during
        // the ~1 s Bluetooth ramp would blank it while the audio is still on the earpiece, and an
        // unknown route (null) is not the earpiece either.
            if (state.current == CallAudioRoute.EARPIECE) {
                acquireProximityWakeLock()
            } else {
                releaseProximityWakeLock()
            }
        }
    }

    private fun acquireProximityWakeLock() {
        if (proximityWakeLock != null) return
        val pm = getSystemService(PowerManager::class.java)
        proximityWakeLock = pm.newWakeLock(
            PowerManager.PROXIMITY_SCREEN_OFF_WAKE_LOCK,
            "firestream:call_proximity"
        )
        proximityWakeLock?.acquire(60 * 60 * 1000L) // 1 hour max
    }

    private fun releaseProximityWakeLock() {
        proximityWakeLock?.let {
            if (it.isHeld) it.release()
        }
        proximityWakeLock = null
    }

    // ──────────────────────────────────────────────────────────────────────────
    // Cleanup
    // ──────────────────────────────────────────────────────────────────────────

    private fun localAvatarPathFor(userId: String?): String? {
        if (userId.isNullOrEmpty()) return null
        val file = profileImageManager.getLocalFile(userId)
        return if (file.exists()) file.absolutePath else null
    }

    private fun endCallWithReason(reason: EndReason) {
        val callId = currentCallId
        if (callId != null) {
            serviceScope.launch {
                callRepository.endCall(callId, reason.name.lowercase())
            }
            writeCallMessageIfCaller(reason)
            callStateHolder.updateState(CallState.Ended(callId, reason))
        }
        cleanup()
    }

    private fun writeCallMessageIfCaller(reason: EndReason) {
        if (!isCaller) return
        val chatId = currentChatId ?: return
        if (callMessageWritten) return
        callMessageWritten = true
        val durationSeconds = callConnectedAt?.let {
            ((System.currentTimeMillis() - it) / 1000).toInt()
        } ?: 0
        serviceScope.launch {
            callRepository.logCallMessage(chatId, reason.name.lowercase(), durationSeconds)
        }
    }

    private fun cleanup() {
        ringTimeoutJob?.cancel()
        signalingJob?.cancel()
        iceCandidateJob?.cancel()

        localAudioTrack?.dispose()
        localAudioTrack = null

        peerConnection?.close()
        peerConnection = null

        webRtcFactory?.dispose()
        webRtcFactory = null

        // Stops the router and releases the proximity wake lock before the mode is restored.
        abandonAudioFocus()

        processedIceCandidates.clear()
        currentCallId = null
        currentChatId = null
        remoteUserId = null
        remoteName = null
        remoteAvatarUrl = null
        callConnectedAt = null
        callMessageWritten = false

        notificationManager?.cancelNotification(CallNotificationManager.NOTIFICATION_ID_INCOMING)

        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        super.onDestroy()
        cleanup()
        serviceScope.cancel()
    }

    private fun stopAndReturn(): Int {
        stopSelf()
        return START_NOT_STICKY
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
