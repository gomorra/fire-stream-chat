package com.firestream.chat.data.call

import android.Manifest
import android.app.Notification
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
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
import androidx.core.content.ContextCompat
import com.firestream.chat.data.util.ProfileImageManager
import com.firestream.chat.domain.model.CallAudioRoute
import com.firestream.chat.domain.model.CallMedia
import com.firestream.chat.domain.model.CallParticipant
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
import org.webrtc.VideoTrack
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import kotlin.concurrent.thread

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
        const val ACTION_SET_CAMERA = "com.firestream.chat.call.SET_CAMERA"
        const val ACTION_FLIP_CAMERA = "com.firestream.chat.call.FLIP_CAMERA"
        const val ACTION_SET_SCREEN_VISIBLE = "com.firestream.chat.call.SET_SCREEN_VISIBLE"

        const val EXTRA_CALL_ID = "call_id"
        const val EXTRA_CHAT_ID = "chat_id"
        const val EXTRA_REMOTE_USER_ID = "remote_user_id"
        const val EXTRA_REMOTE_NAME = "remote_name"
        const val EXTRA_REMOTE_AVATAR_URL = "remote_avatar_url"
        const val EXTRA_AUDIO_ROUTE = "audio_route"
        /** How the call was started: true for a video call. Absent means a voice call. */
        const val EXTRA_VIDEO = "video"
        /** Outgoing only: the callee's app takes a video line. Absent means it does not. */
        const val EXTRA_VIDEO_LINE = "video_line"
        const val EXTRA_CAMERA_ON = "camera_on"
        const val EXTRA_SCREEN_VISIBLE = "screen_visible"

        private const val TAG = "CallService"
        private const val RING_TIMEOUT_MS = 30_000L

        /**
         * @param video how the call is started: as a video call or as a voice call.
         * @param videoLine the callee's app takes a video line, as `CallRepository.createCall`
         *   found out. Never true on a guess: an app without video crashes on such an offer.
         */
        fun startOutgoing(
            context: Context,
            callId: String,
            chatId: String,
            remoteUserId: String,
            remoteName: String,
            remoteAvatarUrl: String?,
            video: Boolean,
            videoLine: Boolean
        ) {
            val intent = Intent(context, CallService::class.java).apply {
                action = ACTION_START_OUTGOING
                putExtra(EXTRA_CALL_ID, callId)
                putExtra(EXTRA_CHAT_ID, chatId)
                putExtra(EXTRA_REMOTE_USER_ID, remoteUserId)
                putExtra(EXTRA_REMOTE_NAME, remoteName)
                putExtra(EXTRA_REMOTE_AVATAR_URL, remoteAvatarUrl)
                putExtra(EXTRA_VIDEO, video)
                putExtra(EXTRA_VIDEO_LINE, videoLine)
            }
            context.startForegroundService(intent)
        }

        fun startIncoming(
            context: Context,
            callId: String,
            remoteUserId: String,
            remoteName: String,
            remoteAvatarUrl: String?,
            video: Boolean
        ) {
            val intent = Intent(context, CallService::class.java).apply {
                action = ACTION_START_INCOMING
                putExtra(EXTRA_CALL_ID, callId)
                putExtra(EXTRA_REMOTE_USER_ID, remoteUserId)
                putExtra(EXTRA_REMOTE_NAME, remoteName)
                putExtra(EXTRA_REMOTE_AVATAR_URL, remoteAvatarUrl)
                putExtra(EXTRA_VIDEO, video)
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

        /**
         * Switch the own camera of the running call on or off. The service never asks for the
         * `CAMERA` permission, and without it the camera stays off, so a screen asks first.
         * Dropped when no call is running.
         */
        fun sendSetCamera(context: Context, on: Boolean) {
            val intent = Intent(context, CallService::class.java).apply {
                action = ACTION_SET_CAMERA
                putExtra(EXTRA_CAMERA_ON, on)
            }
            context.startService(intent)
        }

        /**
         * Tell the running call whether a screen is showing it. The camera runs only while one
         * does. Every call starts as not shown, so a screen reports once the call exists and
         * again whenever it starts or stops showing it. Dropped when no call is running.
         */
        fun sendScreenVisible(context: Context, visible: Boolean) {
            val intent = Intent(context, CallService::class.java).apply {
                action = ACTION_SET_SCREEN_VISIBLE
                putExtra(EXTRA_SCREEN_VISIBLE, visible)
            }
            context.startService(intent)
        }
    }

    @Inject lateinit var callRepository: CallRepository
    @Inject lateinit var callStateHolder: CallStateHolder
    @Inject lateinit var callVideoSinks: CallVideoSinks
    @Inject lateinit var profileImageManager: ProfileImageManager

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /**
     * Guards the media of the running call: [mediaOpen], [webRtcFactory], [localAudioTrack],
     * [localCamera], [cameraTrack], and who is in [sessions]. Opening a session, switching the
     * camera and ending the call all change what is attached to what, and they arrive on the main
     * thread and on [serviceScope]. Held while a session, the camera or [callVideoSinks] is called,
     * which take their own locks under it. Never held together with [audioSessionLock], and never
     * taken in a callback of the camera or of a connection.
     */
    private val mediaLock = Any()

    /** Guarded by [mediaLock]. True from the start of a call until [cleanup] has detached its media. */
    private var mediaOpen = false
    private var webRtcFactory: WebRtcPeerConnectionFactory? = null
    @Volatile private var localAudioTrack: AudioTrack? = null

    /** The camera of this call, made when it is first switched on. Volatile for one identity check. */
    @Volatile private var localCamera: LocalCamera? = null

    /**
     * The camera track while the camera runs, and what every session sends. Written under
     * [mediaLock]. Volatile for [followVideoWithAudio], which only asks whether there is one.
     */
    @Volatile private var cameraTrack: VideoTrack? = null

    /**
     * One [PeerSession] per remote person, keyed by their user id. A 1:1 call has one entry.
     * Entries are added and removed under [mediaLock]; the map is concurrent because events read
     * it without the lock.
     */
    private val sessions = ConcurrentHashMap<String, PeerSession>()
    private var notificationManager: CallNotificationManager? = null

    private var currentCallId: String? = null
    private var currentChatId: String? = null
    private var remoteUserId: String? = null
    private var remoteName: String? = null
    private var remoteAvatarUrl: String? = null
    private var isCaller: Boolean = false

    /**
     * Caller only: the callee's app takes a video line, so the offer carries one. An app without
     * video crashes on such an offer.
     */
    private var calleeTakesVideoLine: Boolean = false

    /**
     * How the current call was started. Set to true only on the main thread: by the intent, or
     * when the callee's side learns it later from the call document. Read from [serviceScope] too.
     */
    @Volatile private var callVideo: Boolean = false
    private var callConnectedAt: Long? = null
    private var callMessageWritten: Boolean = false

    /**
     * What was asked of the camera: the user's switch, and whether a screen shows the call. Both
     * are written on the main thread by the intents and read by [syncCamera]'s job. The camera
     * runs while both are true.
     */
    @Volatile private var cameraWanted = false
    @Volatile private var screenVisible = false

    /** What this side says on the call document, and whether it may say anything there. */
    private val mediaPublisher by lazy { CallMediaPublisher(callRepository, serviceScope) }

    private var ringTimeoutJob: Job? = null
    private var signalingJob: Job? = null
    private var framesJob: Job? = null
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
     * stopped is a no-op inside the router. Only ever held by [startAudioSession],
     * [stopAudioSession] and [followVideoWithAudio], which take the router's and the proximity
     * lock's monitors under it, never the other way round.
     */
    private val audioSessionLock = Any()

    private val mainHandler = Handler(Looper.getMainLooper())

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
                val video = intent.getBooleanExtra(EXTRA_VIDEO, false)
                val videoLine = intent.getBooleanExtra(EXTRA_VIDEO_LINE, false)
                startOutgoingCall(callId, chatId, userId, name, avatar, video = video, videoLine = videoLine)
            }
            ACTION_START_INCOMING -> {
                val callId = intent.getStringExtra(EXTRA_CALL_ID) ?: return stopAndReturn()
                val userId = intent.getStringExtra(EXTRA_REMOTE_USER_ID) ?: return stopAndReturn()
                val name = intent.getStringExtra(EXTRA_REMOTE_NAME) ?: "Unknown"
                val avatar = intent.getStringExtra(EXTRA_REMOTE_AVATAR_URL)
                val video = intent.getBooleanExtra(EXTRA_VIDEO, false)
                startIncomingCall(callId, userId, name, avatar, video)
            }
            ACTION_ANSWER -> answerIncomingCall()
            ACTION_DECLINE -> declineIncomingCall()
            ACTION_HANGUP -> hangup()
            ACTION_TOGGLE_MUTE -> toggleMute()
            ACTION_SELECT_AUDIO_ROUTE -> selectAudioRoute(intent.getStringExtra(EXTRA_AUDIO_ROUTE))
            // A screen can send these three just after the call ended. Without a call they must
            // not leave a started service behind.
            ACTION_SET_CAMERA, ACTION_FLIP_CAMERA, ACTION_SET_SCREEN_VISIBLE -> {
                if (currentCallId == null) {
                    // By start id: the start of the next call may already be on its way, and a
                    // bare stopSelf() would take that one down with this one.
                    stopSelf(startId)
                    return START_NOT_STICKY
                }
                when (intent.action) {
                    ACTION_SET_CAMERA -> setCamera(intent.getBooleanExtra(EXTRA_CAMERA_ON, false))
                    ACTION_FLIP_CAMERA -> flipCamera()
                    else -> setScreenVisible(intent.getBooleanExtra(EXTRA_SCREEN_VISIBLE, false))
                }
            }
        }
        return START_NOT_STICKY
    }

    // ──────────────────────────────────────────────────────────────────────────
    // Outgoing Call Flow
    // ──────────────────────────────────────────────────────────────────────────

    private fun startOutgoingCall(
        callId: String,
        chatId: String,
        userId: String,
        name: String,
        avatar: String?,
        video: Boolean,
        videoLine: Boolean
    ) {
        callVideo = video
        currentCallId = callId
        currentChatId = chatId
        remoteUserId = userId
        remoteName = name
        remoteAvatarUrl = avatar
        isCaller = true
        calleeTakesVideoLine = videoLine
        callMessageWritten = false

        val localAvatar = localAvatarPathFor(userId)
        beginCall(callId, CallParticipant(userId, name, avatar, localAvatar))
        // Known from the start, so no preview runs for a call that cannot send it.
        if (!videoLine) callStateHolder.updateControls { it.copy(videoAvailable = false) }
        callStateHolder.updateState(
            CallState.OutgoingRinging(callId, userId, name, avatar, localAvatar, video)
        )

        val notification = notificationManager!!.buildOutgoingCallNotification(name, video)
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

    private fun startIncomingCall(callId: String, userId: String, name: String, avatar: String?, video: Boolean) {
        callVideo = video
        currentCallId = callId
        remoteUserId = userId
        remoteName = name
        remoteAvatarUrl = avatar
        isCaller = false
        calleeTakesVideoLine = false

        val localAvatar = localAvatarPathFor(userId)
        beginCall(callId, CallParticipant(userId, name, avatar, localAvatar))
        callStateHolder.updateState(
            CallState.IncomingRinging(callId, userId, name, avatar, localAvatar, video)
        )

        val notification = notificationManager!!.buildIncomingCallNotification(name, video)
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

    /**
     * Everything a call starts with, whichever side starts it: fresh controls, the other person,
     * nothing asked of the camera, and its media open.
     */
    private fun beginCall(callId: String, remote: CallParticipant) {
        callStateHolder.beginCall(listOf(remote))
        cameraWanted = false
        screenVisible = false
        mediaPublisher.begin(callId)
        synchronized(mediaLock) { mediaOpen = true }

        framesJob?.cancel()
        framesJob = serviceScope.launch {
            callVideoSinks.framed.collect { callStateHolder.setFramed(it) }
        }
    }

    private fun answerIncomingCall() {
        val callId = currentCallId ?: return

        ringTimeoutJob?.cancel()

        callStateHolder.updateState(
            CallState.Connecting(
                callId, remoteUserId ?: "", remoteName ?: "", remoteAvatarUrl, localAvatarPathFor(remoteUserId), callVideo
            )
        )

        val notification = notificationManager!!.buildOngoingCallNotification(remoteName ?: "Unknown", callVideo)
        // Android 14+ prohibits changing from SHORT_SERVICE to another type directly;
        // exit foreground first, then re-enter as MICROPHONE now that RECORD_AUDIO is granted.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            stopForeground(STOP_FOREGROUND_DETACH)
        }
        // A preview that ran while the call rang carries over: the type names the camera too.
        if (cameraWanted && !startForegroundWithCamera(notification)) {
            cameraWanted = false
            syncCamera()
        }
        if (!cameraWanted) {
            startForeground(
                CallNotificationManager.NOTIFICATION_ID_ONGOING,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
            )
        }

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
        val remoteId = remoteUserId
        signalingJob?.cancel()
        signalingJob = serviceScope.launch {
            // The document changes with every camera and microphone switch of either side. The
            // status is acted on once per change, not once per snapshot.
            var lastStatus: String? = null
            callRepository.observeCallDocument(callId)
                .catch { e -> Log.e(TAG, "Signaling listener error", e) }
                .collectLatest { data ->
                    if (data.video) onCallDocumentSaysVideo(callId)
                    if (remoteId != null) onRemoteMedia(remoteId, data.media[remoteId] ?: CallMedia())
                    if (data.status == lastStatus) return@collectLatest
                    lastStatus = data.status
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

    /**
     * The call document says the call was started as video. The push normally says so first. A
     * function deployed before the kind existed does not, and then the ring starts as a voice call
     * until this arrives.
     */
    private fun onCallDocumentSaysVideo(callId: String) {
        // Fast path: every later snapshot of the document says the same.
        if (callVideo) return
        // On the main thread, where the intents run. A call that starts or is answered there
        // cannot interleave: a late write must not reach the next call, and answering posts the
        // ongoing notification under the id the ring uses.
        mainHandler.post {
            if (callVideo || currentCallId != callId) return@post
            callVideo = true
            // A video call plays on the speaker by default.
            serviceScope.launch { followVideoWithAudio() }
            val ringing = callStateHolder.markVideo(callId) as? CallState.IncomingRinging ?: return@post
            val manager = notificationManager ?: return@post
            manager.updateNotification(
                manager.buildIncomingCallNotification(ringing.callerName, video = true),
                CallNotificationManager.NOTIFICATION_ID_ONGOING
            )
            // cleanup() can run on serviceScope. If the call ended while the ring was posted,
            // the post may have landed after the foreground notification was removed.
            if (callStateHolder.callState.value != ringing) {
                manager.cancelNotification(CallNotificationManager.NOTIFICATION_ID_ONGOING)
            }
        }
    }

    /**
     * What the other side says about their camera and microphone, from the call document. Someone
     * who has written nothing reads as camera off, microphone on.
     */
    private fun onRemoteMedia(remoteId: String, media: CallMedia) {
        val known = callStateHolder.participants.value.firstOrNull { it.id == remoteId } ?: return
        if (known.cameraOn == media.camera && known.micOn == media.mic) return
        // Their track stays the same through a switch, and a view keeps the last picture it drew.
        // The video shows again only with a frame that arrived after the switch.
        if (known.cameraOn != media.camera) callVideoSinks.awaitFrame(remoteId)
        callStateHolder.updateParticipant(remoteId) { it.copy(cameraOn = media.camera, micOn = media.mic) }
        followVideoWithAudio()
    }

    /** The callee picked up. The session applies the answer; this only moves the call's state on. */
    private fun onCallAnswered(callId: String) {
        ringTimeoutJob?.cancel()

        // Only the ringing call moves to "connecting", and only if it is still ringing when the
        // write lands. The session watches the same document and may connect at any moment, and
        // a later snapshot of an answered call must not take the screen back.
        val ringing = callStateHolder.callState.value as? CallState.OutgoingRinging ?: return
        val connecting = CallState.Connecting(
            callId, remoteUserId ?: "", remoteName ?: "", remoteAvatarUrl, localAvatarPathFor(remoteUserId), callVideo
        )
        if (!callStateHolder.compareAndSetState(ringing, connecting)) return

        val notification = notificationManager!!.buildOngoingCallNotification(remoteName ?: "Unknown", callVideo)
        notificationManager!!.updateNotification(notification, CallNotificationManager.NOTIFICATION_ID_ONGOING)
    }

    // ──────────────────────────────────────────────────────────────────────────
    // Peer Sessions
    // ──────────────────────────────────────────────────────────────────────────

    /**
     * Open the connection to [remoteId] and start negotiating. The factory and the microphone
     * track are created with the first session and shared by every later one. In a 1:1 call the
     * caller makes the offer. Does nothing once the call's media is detached.
     */
    private fun openSession(callId: String, remoteId: String) {
        synchronized(mediaLock) {
            if (sessions.containsKey(remoteId)) return
            val factory = factoryOrNull() ?: return
            val audioTrack = localAudioTrack
                ?: factory.createAudioTrack().also { localAudioTrack = it }

            val session = PeerSession(
                factory = factory,
                signaling = OneToOneSignaling(callRepository, callId, isCaller),
                localTracks = listOf(audioTrack),
                offers = isCaller,
                offerVideoLine = calleeTakesVideoLine,
                scope = serviceScope,
                logTag = TAG
            )
            sessions[remoteId] = session
            // Ends by itself: the flow completes when the session is closed.
            serviceScope.launch {
                session.events.collect { event -> onSessionEvent(callId, remoteId, session, event) }
            }
            session.start()
            // A camera that already runs, as a preview, goes out once the video line is agreed.
            session.setCamera(cameraTrack)
        }
    }

    /**
     * The factory of this call, made on first use. Null once [cleanup] has detached the call's
     * media, so nothing is built for a call that is over. Caller holds [mediaLock].
     */
    private fun factoryOrNull(): WebRtcPeerConnectionFactory? {
        if (!mediaOpen) return null
        return webRtcFactory ?: WebRtcPeerConnectionFactory(applicationContext).also {
            webRtcFactory = it
            callVideoSinks.open(it.eglContext)
        }
    }

    /**
     * Runs on [serviceScope], never on the WebRTC signalling thread, so it may end the call:
     * closing a connection from inside one of its own callbacks deadlocks.
     */
    private fun onSessionEvent(callId: String, remoteId: String, session: PeerSession, event: PeerSessionEvent) {
        // An event that was still queued when its call ended must not touch the next call.
        if (sessions[remoteId] !== session) return
        when (event) {
            PeerSessionEvent.Connected -> onSessionConnected(callId, remoteId, session)
            PeerSessionEvent.Disconnected ->
                callStateHolder.updateParticipant(remoteId) { it.copy(connected = false) }
            is PeerSessionEvent.Failed -> endCallWithReason(EndReason.ERROR)
            // Remote audio plays without a sink. Remote video needs the views.
            is PeerSessionEvent.RemoteTrack -> {
                val track = event.track as? VideoTrack ?: return
                synchronized(mediaLock) {
                    if (sessions[remoteId] === session) callVideoSinks.setTrack(remoteId, track)
                }
            }
            is PeerSessionEvent.VideoLine -> onVideoLine(callId, remoteId, session, event.available)
        }
    }

    private fun onSessionConnected(callId: String, remoteId: String, session: PeerSession) {
        if (currentCallId == null) return
        ringTimeoutJob?.cancel()
        if (callConnectedAt == null) callConnectedAt = System.currentTimeMillis()
        // The proximity lock is not taken here: it follows the audio route, and
        // startAudioSession's collector applies it as soon as the OS reports one.
        startAudioSession()
        // Does nothing for a call that is over: the publisher knows which call it serves.
        mediaPublisher.onConnected(callId)
        // cleanup() empties the map before it stops the audio session. If the call ended on
        // another thread while this ran, that stop may already be behind us, so undo the start.
        if (sessions[remoteId] !== session) {
            stopAudioSession()
            return
        }
        callStateHolder.updateParticipant(remoteId) { it.copy(connected = true) }
        callStateHolder.updateState(
            CallState.Connected(
                callId,
                remoteUserId ?: "",
                remoteName ?: "",
                remoteAvatarUrl,
                System.currentTimeMillis(),
                localAvatarPathFor(remoteUserId),
                callVideo
            )
        )
        // The kind can arrive on the main thread between the read above and the write.
        if (callVideo) callStateHolder.markVideo(callId)
    }

    /** Offer and answer are applied, and the session knows whether both sides take video. */
    private fun onVideoLine(callId: String, remoteId: String, session: PeerSession, available: Boolean) {
        callStateHolder.updateControls { it.copy(videoAvailable = available) }
        // Only a call with an agreed video line is ever written to, see CallMediaPublisher.
        mediaPublisher.onVideoLine(callId, available)
        if (available) return
        // On screen this is a voice call now, so it leaves the speaker default of a video call.
        serviceScope.launch { followVideoWithAudio() }
        // An app without video on the other side. A preview that already runs would look like a
        // camera that is being sent, so it goes off. On the main thread, where the camera is switched.
        mainHandler.post {
            if (sessions[remoteId] === session && cameraWanted) setCamera(false)
        }
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
        val muted = callStateHolder.toggleMute()
        localAudioTrack?.setEnabled(!muted)
        mediaPublisher.update { it.copy(mic = !muted) }
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
    // Camera
    // ──────────────────────────────────────────────────────────────────────────

    /**
     * The user switched the camera. It goes on only with the `CAMERA` permission, which the
     * service never asks for, and only while the other side takes video. Main thread.
     */
    private fun setCamera(on: Boolean) {
        val wanted = on && hasCameraPermission() && callStateHolder.uiControls.value.videoAvailable
        if (on && !wanted) Log.w(TAG, "The camera stays off: no permission, or the other side takes no video")
        cameraWanted = wanted
        // The type follows the switch, not the pause: it is set from the screen the user tapped
        // on, while the app is visible. A refused type leaves the camera off and the call running.
        if (!applyForegroundType()) {
            Log.w(TAG, "The camera stays off: the system refused the camera foreground type")
            cameraWanted = false
        }
        syncCamera()
    }

    /** A screen started or stopped showing the call. The camera pauses while none does. Main thread. */
    private fun setScreenVisible(visible: Boolean) {
        screenVisible = visible
        syncCamera()
    }

    private fun flipCamera() {
        serviceScope.launch {
            synchronized(mediaLock) { localCamera?.flip() }
        }
    }

    /**
     * Make the camera, the sessions, the screen and the call document follow [cameraWanted] and
     * [screenVisible]. Safe from any thread and as often as wanted: the job reads both when it
     * runs, so the last one to run leaves the camera where the last request put it.
     */
    private fun syncCamera() {
        publishCameraControls()
        // The camera calls wait for the camera thread, so they never run on the main thread.
        serviceScope.launch {
            synchronized(mediaLock) { driveCamera() }
            publishCameraControls()
            followVideoWithAudio()
        }
    }

    private fun publishCameraControls() {
        val wanted = cameraWanted
        val paused = wanted && !screenVisible
        callStateHolder.updateControls { it.copy(cameraOn = wanted, cameraPaused = paused) }
    }

    /** Caller holds [mediaLock]. */
    private fun driveCamera() {
        if (!mediaOpen) return
        val track = if (cameraWanted && screenVisible) startCamera() else null
        // Off the connections first, then the capturer.
        sessions.values.forEach { it.setCamera(track) }
        if (track == null) {
            localCamera?.let { camera ->
                camera.stop()
                callVideoSinks.awaitFrame(CallVideoSinks.LOCAL)
            }
        }
        cameraTrack = track
        // While the screen is away the other side is told the camera is off, and shows the avatar.
        mediaPublisher.update { it.copy(camera = track != null) }
    }

    /**
     * Caller holds [mediaLock].
     *
     * @return the track the camera captures into, or null when it cannot run. Then the switch
     *   goes back to off: the device has no camera, or it did not open.
     */
    private fun startCamera(): VideoTrack? {
        val factory = factoryOrNull() ?: return null
        val camera = localCamera ?: newCamera(factory).also { localCamera = it }
        val track = camera.start()
        if (track == null) {
            cameraWanted = false
            // The foreground type names a camera that is off. Set it again, on the main thread.
            mainHandler.post { if (localCamera === camera && !cameraWanted) applyForegroundType() }
            return null
        }
        // A device without a front camera starts on the back one.
        publishFacing(camera.isFront)
        callVideoSinks.setTrack(CallVideoSinks.LOCAL, track)
        return track
    }

    /** The camera in use is the front one, or not. The self view mirrors only the front camera. */
    private fun publishFacing(front: Boolean) {
        callStateHolder.updateControls { it.copy(frontCamera = front) }
        callVideoSinks.setLocalMirrored(front)
    }

    /**
     * The camera's callbacks run on the camera thread. They never take [mediaLock]: a thread that
     * holds it may be waiting for the camera thread.
     */
    private fun newCamera(factory: WebRtcPeerConnectionFactory): LocalCamera {
        lateinit var camera: LocalCamera
        camera = LocalCamera(
            context = applicationContext,
            factory = factory,
            onFlipped = ::publishFacing,
            onFailure = { reason ->
                Log.w(TAG, "The camera stopped: $reason")
                // Another app took the camera, or it broke. The switch goes off; the call goes on.
                mainHandler.post { if (localCamera === camera && cameraWanted) setCamera(false) }
            }
        )
        return camera
    }

    private fun hasCameraPermission(): Boolean =
        ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED

    /**
     * Set the foreground type of a call past its incoming ring again: the microphone, and the
     * camera while it is switched on. An incoming ring is a short service, and answering sets the
     * type. Main thread.
     *
     * @return false when the camera was wanted and the system refused the type. On Android 14 and
     *   later that happens without the `CAMERA` permission and while the app is not visible.
     */
    private fun applyForegroundType(): Boolean {
        val manager = notificationManager ?: return true
        val name = remoteName ?: "Unknown"
        val notification = when (callStateHolder.callState.value) {
            is CallState.OutgoingRinging -> manager.buildOutgoingCallNotification(name, callVideo)
            is CallState.Connecting, is CallState.Connected -> manager.buildOngoingCallNotification(name, callVideo)
            else -> return true
        }
        if (cameraWanted) return startForegroundWithCamera(notification)
        try {
            startForeground(
                CallNotificationManager.NOTIFICATION_ID_ONGOING,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
            )
        } catch (e: RuntimeException) {
            // The service keeps the type it had. A camera type left over allows nothing: the
            // camera is off.
            Log.w(TAG, "Could not drop the camera foreground type", e)
        }
        return true
    }

    /** @return false when the system refused the type. The service then keeps the type it had. */
    private fun startForegroundWithCamera(notification: Notification): Boolean = try {
        startForeground(
            CallNotificationManager.NOTIFICATION_ID_ONGOING,
            notification,
            ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE or ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA
        )
        true
    } catch (e: SecurityException) {
        Log.w(TAG, "Camera foreground type refused", e)
        false
    } catch (e: IllegalStateException) {
        // ForegroundServiceStartNotAllowedException: the app is not in a state to start one.
        Log.w(TAG, "Camera foreground type refused", e)
        false
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
        val router = CallAudioRouter(am, mainHandler)
        val proximity = ProximityLock(getSystemService(PowerManager::class.java))
        audioRouter = router
        proximityLock = proximity
        // Before the first routing, so a call with video starts on the speaker.
        followVideoWithAudio()
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

    /**
     * Audio follows video. A call started as video that can carry video, or one where video shows
     * right now on either side, plays on the speaker unless a headset is connected or the user
     * picked a route. A call started as video to an app without video is a voice call on screen,
     * and starts on the earpiece. While video shows the proximity lock is off. Does nothing before
     * the audio session has started. Not for the main thread: it takes [audioSessionLock].
     */
    private fun followVideoWithAudio() = synchronized(audioSessionLock) {
        val showing = cameraTrack != null || callStateHolder.participants.value.any { it.cameraOn }
        val startedAsVideo = callVideo && callStateHolder.uiControls.value.videoAvailable
        audioRouter?.setPreferSpeaker(startedAsVideo || showing)
        proximityLock?.setVideoShowing(showing)
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
        // Read here: cleanup() resets it before the launched write runs.
        val video = callVideo
        serviceScope.launch {
            callRepository.logCallMessage(chatId, reason.name.lowercase(), durationSeconds, video)
        }
    }

    /**
     * The media of one call, taken out of the service by [detachMedia]. Nothing else refers to it
     * any more, so [release] can run on its own thread while the next call starts.
     */
    private class DetachedMedia(
        val sessions: List<PeerSession>,
        val camera: LocalCamera?,
        val audioTrack: AudioTrack?,
        val factory: WebRtcPeerConnectionFactory?
    )

    /**
     * Take the call's media out of the service, as one step. After it, no session is in the map,
     * no view holds a track, and nothing new is built for this call.
     */
    private fun detachMedia(): DetachedMedia = synchronized(mediaLock) {
        mediaOpen = false
        // The views let go of every track before anything is disposed.
        callVideoSinks.close()
        // Out of the map, so an event still queued for a session finds it gone.
        val open = sessions.values.toList()
        sessions.clear()
        val media = DetachedMedia(open, localCamera, localAudioTrack, webRtcFactory)
        localCamera = null
        cameraTrack = null
        localAudioTrack = null
        webRtcFactory = null
        media
    }

    /**
     * Dispose a call's media in the one order that is safe: capturer, texture helper, video
     * source, tracks, connections, factory, EGL context. It waits for the camera thread and the
     * WebRTC threads, so it runs on a thread of its own and never on the one that ended the call.
     */
    private fun releaseMedia(media: DetachedMedia) {
        // Sessions, the microphone track and the camera are only ever built on a factory.
        if (media.factory == null) return
        thread(name = "CallMediaRelease") {
            // Each step on its own: a camera that fails to close must not keep the connection
            // open and the microphone on it.
            var clean = releaseStep("microphone off") { media.audioTrack?.setEnabled(false) }
            // The camera track comes off every connection before it is disposed.
            clean = releaseStep("camera off the connections") { media.sessions.forEach { it.setCamera(null) } } && clean
            clean = releaseStep("camera") { media.camera?.dispose() } && clean
            clean = releaseStep("microphone") { media.audioTrack?.dispose() } && clean
            clean = releaseStep("connections") { media.sessions.forEach { it.close() } } && clean
            // The factory goes only when everything made on it is gone. Otherwise it leaks,
            // which is better than a native crash after the hang-up.
            if (clean) releaseStep("factory") { media.factory?.dispose() }
        }
    }

    /** @return false when [block] threw. The call is over either way, so the failure is only logged. */
    private inline fun releaseStep(what: String, block: () -> Unit): Boolean = try {
        block()
        true
    } catch (e: RuntimeException) {
        Log.e(TAG, "Releasing the call's media failed at: $what", e)
        false
    }

    private fun cleanup() {
        ringTimeoutJob?.cancel()
        signalingJob?.cancel()
        framesJob?.cancel()
        mediaPublisher.end()

        releaseMedia(detachMedia())

        stopAudioSession()

        cameraWanted = false
        screenVisible = false
        // The views are gone with the tracks, so no tile claims a picture any more.
        callStateHolder.updateControls { it.copy(cameraOn = false, cameraPaused = false) }
        callStateHolder.setFramed(emptySet())

        currentCallId = null
        currentChatId = null
        remoteUserId = null
        remoteName = null
        remoteAvatarUrl = null
        callConnectedAt = null
        callMessageWritten = false
        callVideo = false
        calleeTakesVideoLine = false

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
