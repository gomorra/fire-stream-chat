package com.firestream.chat.data.call

import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.util.Log
import com.firestream.chat.data.util.ProfileImageManager
import com.firestream.chat.domain.model.CallAudioRoute
import com.firestream.chat.domain.model.CallState
import com.firestream.chat.domain.model.EndReason
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
import java.util.concurrent.ConcurrentHashMap
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

        fun sendAction(context: Context, action: String) {
            val intent = Intent(context, CallService::class.java).apply {
                this.action = action
            }
            context.startService(intent)
        }

        /** Ask the ongoing call to move its audio to [route]. No-op if no call is running. */
        fun sendSelectAudioRoute(context: Context, route: CallAudioRoute) {
            val intent = Intent(context, CallService::class.java).apply {
                action = ACTION_SELECT_AUDIO_ROUTE
                putExtra(EXTRA_AUDIO_ROUTE, route.name)
            }
            context.startService(intent)
        }
    }

    @Inject lateinit var callRepository: CallRepository
    @Inject lateinit var callStateHolder: CallStateHolder
    @Inject lateinit var profileImageManager: ProfileImageManager

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private var webRtcFactory: WebRtcPeerConnectionFactory? = null
    private var localAudioTrack: AudioTrack? = null

    /**
     * One [PeerSession] per remote person, keyed by their user id. A 1:1 call has one entry.
     * Sessions are opened from the main thread and closed by [cleanup] from whichever thread ends
     * the call.
     */
    private val sessions = ConcurrentHashMap<String, PeerSession>()
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
    private var routeJob: Job? = null

    private var audioManager: AudioManager? = null
    private var audioFocusRequest: AudioFocusRequest? = null
    // Written under audioSessionLock, but read without it by selectAudioRoute() on the main thread:
    // taking the lock there would park the main thread behind startAudioSession()'s binder calls.
    @Volatile private var audioRouter: CallAudioRouter? = null
    private var proximityLock: ProximityLock? = null

    private var previousAudioMode: Int = AudioManager.MODE_NORMAL

    /**
     * Guards the audio-session fields above ([audioFocusRequest], [audioRouter], [proximityLock],
     * [routeJob], [previousAudioMode]). They are written from [serviceScope], where a session's
     * connect event is handled, and from both teardown threads. [audioRouter] has one lock-free
     * reader, [selectAudioRoute], and is volatile for it. A tap that lands on a router already
     * stopped is a no-op inside the router. Only ever held by [startAudioSession] /
     * [stopAudioSession], which take the router's and the proximity lock's monitors under it,
     * never the other way round.
     */
    private val audioSessionLock = Any()

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
            ACTION_SELECT_AUDIO_ROUTE -> selectAudioRoute(intent.getStringExtra(EXTRA_AUDIO_ROUTE))
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

        openSession(callId, userId)
        observeCallDocument(callId)
        startRingTimeout()
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

        // The session fetches the offer, answers it, and writes the answer with status="answered".
        openSession(callId, remoteUserId ?: "")
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
                                onCallAnswered(callId)
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

    /** The callee picked up. The session applies the answer; this only moves the call's state on. */
    private fun onCallAnswered(callId: String) {
        ringTimeoutJob?.cancel()

        // Only the ringing call moves to "connecting", and only if it is still ringing when the
        // write lands. The session watches the same document and may connect at any moment, and
        // a later snapshot of an answered call must not take the screen back.
        val ringing = callStateHolder.callState.value as? CallState.OutgoingRinging ?: return
        val connecting = CallState.Connecting(
            callId, remoteUserId ?: "", remoteName ?: "", remoteAvatarUrl, localAvatarPathFor(remoteUserId)
        )
        if (!callStateHolder.compareAndSetState(ringing, connecting)) return

        val notification = notificationManager!!.buildOngoingCallNotification(remoteName ?: "Unknown")
        notificationManager!!.updateNotification(notification, CallNotificationManager.NOTIFICATION_ID_ONGOING)
    }

    // ──────────────────────────────────────────────────────────────────────────
    // Peer Sessions
    // ──────────────────────────────────────────────────────────────────────────

    /**
     * Open the connection to [remoteId] and start negotiating. The factory and the microphone
     * track are created with the first session and shared by every later one. In a 1:1 call the
     * caller makes the offer.
     */
    private fun openSession(callId: String, remoteId: String) {
        if (sessions.containsKey(remoteId)) return

        val factory = webRtcFactory
            ?: WebRtcPeerConnectionFactory(applicationContext).also { webRtcFactory = it }
        val audioTrack = localAudioTrack
            ?: factory.createAudioTrack().also { localAudioTrack = it }

        val session = PeerSession(
            factory = factory,
            signaling = OneToOneSignaling(callRepository, callId, isCaller),
            localTracks = listOf(audioTrack),
            offers = isCaller,
            scope = serviceScope,
            logTag = TAG
        )
        sessions[remoteId] = session
        // Ends by itself: the flow completes when the session is closed.
        serviceScope.launch {
            session.events.collect { event -> onSessionEvent(remoteId, session, event) }
        }
        session.start()
    }

    /**
     * Runs on [serviceScope], never on the WebRTC signalling thread, so it may end the call:
     * closing a connection from inside one of its own callbacks deadlocks.
     */
    private fun onSessionEvent(remoteId: String, session: PeerSession, event: PeerSessionEvent) {
        // An event that was still queued when its call ended must not touch the next call.
        if (sessions[remoteId] !== session) return
        when (event) {
            PeerSessionEvent.Connected -> onSessionConnected(remoteId, session)
            PeerSessionEvent.Disconnected -> {}
            is PeerSessionEvent.Failed -> endCallWithReason(EndReason.ERROR)
            // Remote audio plays without a sink.
            is PeerSessionEvent.RemoteTrack -> {}
        }
    }

    private fun onSessionConnected(remoteId: String, session: PeerSession) {
        val callId = currentCallId ?: return
        ringTimeoutJob?.cancel()
        if (callConnectedAt == null) callConnectedAt = System.currentTimeMillis()
        // The proximity lock is not taken here: it follows the audio route, and
        // startAudioSession's collector applies it as soon as the OS reports one.
        startAudioSession()
        // cleanup() empties the map before it stops the audio session. If the call ended on
        // another thread while this ran, that stop may already be behind us, so undo the start.
        if (sessions[remoteId] !== session) {
            stopAudioSession()
            return
        }
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

    private fun selectAudioRoute(routeName: String?) {
        val route = CallAudioRoute.entries.firstOrNull { it.name == routeName }
        if (route == null) {
            Log.w(TAG, "Ignoring audio-route request for unknown route '$routeName'")
            return
        }
        audioRouter?.select(route)
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
    // Audio Session — focus, mode, routing, proximity
    // ──────────────────────────────────────────────────────────────────────────

    /**
     * Take audio focus, switch the device into communication mode, and start routing. Idempotent:
     * a session that reconnects reports connected again, and a second run would both overwrite
     * [previousAudioMode] with `MODE_IN_COMMUNICATION` and leak a second router and collector.
     *
     * Runs under [audioSessionLock] because it is called from [serviceScope] while
     * [stopAudioSession] arrives from the main thread (`ACTION_HANGUP`) and from [serviceScope]:
     * hanging up as ICE connects must not interleave into a half-started session that nothing
     * then tears down.
     */
    private fun startAudioSession() = synchronized(audioSessionLock) {
        val am = audioManager ?: return
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

        // Only now, in MODE_IN_COMMUNICATION, does the OS list communication devices.
        val router = CallAudioRouter(am, Handler(Looper.getMainLooper()))
        val proximity = ProximityLock(getSystemService(PowerManager::class.java))
        audioRouter = router
        proximityLock = proximity
        router.start()

        routeJob = serviceScope.launch {
            router.state.collect { routeState ->
                callStateHolder.updateAudioRoutes(routeState.available, routeState.current)
                // Before the OS names a route the audio is on the earpiece — that is what
                // MODE_IN_COMMUNICATION starts on — so the lock is taken as it always was, and
                // released the moment a headset or the speaker is reported.
                proximity.follow(routeState.current ?: CallAudioRoute.EARPIECE)
            }
        }
    }

    /** Undo [startAudioSession], in the reverse order: proximity, routing, focus, mode. */
    private fun stopAudioSession() = synchronized(audioSessionLock) {
        // Latches before routeJob is cancelled: cancellation is cooperative, so a collector body
        // already running could otherwise re-acquire the lock after the call is gone.
        proximityLock?.shutdown()
        proximityLock = null
        routeJob?.cancel()
        routeJob = null
        // Hands the device back before the mode is restored, or the next media app inherits SCO.
        audioRouter?.stop()
        audioRouter = null

        val am = audioManager ?: return
        audioFocusRequest?.let { am.abandonAudioFocusRequest(it) }
        am.mode = previousAudioMode
        audioFocusRequest = null
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

        localAudioTrack?.dispose()
        localAudioTrack = null

        // Out of the map first, so an event still queued for a session finds it gone.
        val open = sessions.values.toList()
        sessions.clear()
        open.forEach { it.close() }

        webRtcFactory?.dispose()
        webRtcFactory = null

        stopAudioSession()

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
