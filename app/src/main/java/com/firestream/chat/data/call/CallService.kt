package com.firestream.chat.data.call

import android.Manifest
import android.app.KeyguardManager
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
import com.firestream.chat.data.util.ProfileImageManager
import com.firestream.chat.di.ApplicationScope
import com.firestream.chat.domain.model.CallAudioRoute
import com.firestream.chat.domain.model.EndReason
import com.firestream.chat.domain.repository.CallRepository
import com.firestream.chat.domain.repository.ChatRepository
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * The foreground service that holds a call while it rings and runs. It owns what Android provides:
 * the foreground notification and its type, the audio session and the permissions. Each call itself
 * runs in a [CallSession], which reaches those through [host]. The service holds no call logic.
 *
 * Threading: everything runs on the main thread. Intents and lifecycle callbacks arrive there,
 * [serviceScope] dispatches there, and [WebRtcCallLocalMedia] brings WebRTC's and the camera's
 * callbacks there. The fields therefore need no locks, and teardown never races itself.
 */
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

        private const val TAG = "CallService"

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
         * Answer the ringing call, with the own camera on or off. One intent for both, so a
         * preview that ran while the call rang never outlives a "voice only" answer. The screen
         * asks for the microphone first, and for the camera when [camera] is true.
         */
        fun sendAnswer(context: Context, camera: Boolean) {
            val intent = Intent(context, CallService::class.java).apply {
                action = ACTION_ANSWER
                putExtra(EXTRA_CAMERA_ON, camera)
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
    }

    @Inject lateinit var callRepository: CallRepository
    @Inject lateinit var chatRepository: ChatRepository
    @Inject lateinit var callStateHolder: CallStateHolder
    @Inject lateinit var callVideoSinks: CallVideoSinks
    @Inject lateinit var iceServerProvider: IceServerProvider
    @Inject lateinit var profileImageManager: ProfileImageManager

    /**
     * For the writes that record how a call ended, and for the release of its media. They must
     * outlive the service: a call that ends stops the service right after launching them, and
     * [onDestroy] cancels [serviceScope].
     */
    @Inject @ApplicationScope lateinit var appScope: CoroutineScope

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private var notificationManager: CallNotificationManager? = null

    /** The call this service holds. One at a time. */
    private var session: CallSession? = null

    /**
     * The notification of the held call past its incoming ring, kept for a change of the
     * foreground type: `startForeground` takes the type together with a notification.
     */
    private var typedNotification: Notification? = null

    private var routeJob: Job? = null
    private var audioManager: AudioManager? = null
    private var audioFocusRequest: AudioFocusRequest? = null
    private var audioRouter: CallAudioRouter? = null
    private var proximityLock: ProximityLock? = null

    /** What [CallHost.setAudioFollowsVideo] said last. Applied to an audio session when it starts. */
    private var preferSpeaker = false
    private var videoShowing = false

    private var previousAudioMode: Int = AudioManager.MODE_NORMAL

    /** The id of the latest start this service has seen, for [stopSelf]. */
    private var lastStartId = 0

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        audioManager = getSystemService(AudioManager::class.java)
        notificationManager = CallNotificationManager(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        lastStartId = startId
        when (intent?.action) {
            ACTION_START_OUTGOING -> {
                val callId = intent.getStringExtra(EXTRA_CALL_ID) ?: return stopIfIdle()
                val chatId = intent.getStringExtra(EXTRA_CHAT_ID) ?: return stopIfIdle()
                val userId = intent.getStringExtra(EXTRA_REMOTE_USER_ID) ?: return stopIfIdle()
                val name = intent.getStringExtra(EXTRA_REMOTE_NAME) ?: "Unknown"
                val avatar = intent.getStringExtra(EXTRA_REMOTE_AVATAR_URL)
                val video = intent.getBooleanExtra(EXTRA_VIDEO, false)
                val videoLine = intent.getBooleanExtra(EXTRA_VIDEO_LINE, false)
                startOutgoingCall(callId, chatId, userId, name, avatar, video = video, videoLine = videoLine)
            }
            ACTION_START_INCOMING -> {
                val callId = intent.getStringExtra(EXTRA_CALL_ID) ?: return stopIfIdle()
                val userId = intent.getStringExtra(EXTRA_REMOTE_USER_ID) ?: return stopIfIdle()
                val name = intent.getStringExtra(EXTRA_REMOTE_NAME) ?: "Unknown"
                val avatar = intent.getStringExtra(EXTRA_REMOTE_AVATAR_URL)
                val video = intent.getBooleanExtra(EXTRA_VIDEO, false)
                startIncomingCall(callId, userId, name, avatar, video)
            }
            ACTION_ANSWER -> session?.answer(camera = intent.getBooleanExtra(EXTRA_CAMERA_ON, false))
            ACTION_DECLINE -> decline(intent.getStringExtra(EXTRA_CALL_ID))
            ACTION_HANGUP -> session?.hangup()
            ACTION_TOGGLE_MUTE -> session?.toggleMute()
            ACTION_SELECT_AUDIO_ROUTE -> selectAudioRoute(intent.getStringExtra(EXTRA_AUDIO_ROUTE))
            ACTION_SET_CAMERA -> session?.setCamera(intent.getBooleanExtra(EXTRA_CAMERA_ON, false))
            ACTION_FLIP_CAMERA -> session?.flipCamera()
        }
        // An action that finds no call, such as a second tap on Hang Up or a camera switch sent
        // just after the call ended, must not leave the service running.
        return stopIfIdle()
    }

    private fun startOutgoingCall(
        callId: String,
        chatId: String,
        userId: String,
        name: String,
        avatar: String?,
        video: Boolean,
        videoLine: Boolean,
    ) {
        session?.let { current ->
            // Another call holds the service. Its connection must not be reused for this one.
            // The new call's document already exists and has rung the callee, so end it rather
            // than leave their phone ringing.
            Log.w(TAG, "Ending outgoing call $callId: call ${current.callId} is in progress")
            appScope.launch { callRepository.endCall(callId, EndReason.HANGUP) }
            return
        }
        // Held before it starts: a session that ends while starting must find itself here to let go.
        val call = newSession(callId, isCaller = true, chatId, userId, name, avatar, video, videoLine)
        session = call
        call.startOutgoing()
    }

    private fun startIncomingCall(callId: String, userId: String, name: String, avatar: String?, video: Boolean) {
        session?.let { current ->
            // FCMService checks this too, but a call can start between its check and this intent.
            Log.w(TAG, "Ignoring incoming call $callId: call ${current.callId} is in progress")
            return
        }
        // The side that answers takes the video line the offer brings, so it offers none itself.
        val call = newSession(callId, isCaller = false, chatId = null, userId, name, avatar, video, videoLine = false)
        session = call
        call.startIncoming()
    }

    private fun newSession(
        callId: String,
        isCaller: Boolean,
        chatId: String?,
        userId: String,
        name: String,
        avatar: String?,
        video: Boolean,
        videoLine: Boolean,
    ) = CallSession(
        callId = callId,
        isCaller = isCaller,
        chatId = chatId,
        remoteUserId = userId,
        remoteName = name,
        remoteAvatarUrl = avatar,
        video = video,
        offerVideoLine = videoLine,
        repository = callRepository,
        chatRepository = chatRepository,
        iceServers = iceServerProvider,
        stateHolder = callStateHolder,
        host = host,
        parentScope = serviceScope,
        appScope = appScope,
    )

    /**
     * Decline [callId], or the ringing call this service holds when it is null. The fallback ring
     * names a call that no session holds, because Android would not start this service for it.
     * That call is declined directly, and its ring goes.
     */
    private fun decline(callId: String?) {
        val current = session
        if (current != null && (callId == null || callId == current.callId)) {
            current.decline()
            return
        }
        if (callId == null) return
        notificationManager?.cancelNotification(CallNotificationManager.NOTIFICATION_ID_RING_FALLBACK)
        appScope.launch { callRepository.declineCall(callId) }
    }

    private fun selectAudioRoute(routeName: String?) {
        val route = CallAudioRoute.entries.firstOrNull { it.name == routeName }
        if (route == null) {
            Log.w(TAG, "Ignoring audio-route request for unknown route '$routeName'")
            return
        }
        audioRouter?.select(route)
    }

    /** What a [CallSession] needs from Android. */
    private val host = object : CallHost {
        override fun hasMicrophonePermission(): Boolean = isGranted(Manifest.permission.RECORD_AUDIO)

        override fun hasCameraPermission(): Boolean = isGranted(Manifest.permission.CAMERA)

        override fun isLocked(): Boolean = getSystemService(KeyguardManager::class.java).isKeyguardLocked

        override fun localAvatarPath(userId: String): String? {
            if (userId.isEmpty()) return null
            val file = profileImageManager.getLocalFile(userId)
            return if (file.exists()) file.absolutePath else null
        }

        override fun foregroundOutgoing(remoteName: String, video: Boolean) {
            val notification = notificationManager!!.buildOutgoingCallNotification(remoteName, video)
            typedNotification = notification
            startForegroundWithMicrophone(notification)
        }

        override fun foregroundIncoming(remoteName: String, video: Boolean) {
            val notification = notificationManager!!.buildIncomingCallNotification(remoteName, video)
            // API 34+ enforces RECORD_AUDIO at startForeground() for MICROPHONE type;
            // use SHORT_SERVICE during ringing since the mic isn't needed yet.
            // Pre-34 doesn't enforce this, and SHORT_SERVICE doesn't exist, so MICROPHONE is safe.
            val serviceType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SHORT_SERVICE
            } else {
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
            }
            startForeground(CallNotificationManager.NOTIFICATION_ID_ONGOING, notification, serviceType)
            // This ring replaces the one FCMService posts when it cannot start the service.
            notificationManager!!.cancelNotification(CallNotificationManager.NOTIFICATION_ID_RING_FALLBACK)
        }

        override fun showIncoming(remoteName: String, video: Boolean) {
            val notifications = notificationManager!!
            notifications.updateNotification(
                notifications.buildIncomingCallNotification(remoteName, video),
                CallNotificationManager.NOTIFICATION_ID_ONGOING
            )
        }

        override fun foregroundOngoing(remoteName: String, video: Boolean, camera: Boolean): Boolean {
            val notification = notificationManager!!.buildOngoingCallNotification(remoteName, video)
            typedNotification = notification
            // Android 14+ prohibits changing from SHORT_SERVICE to another type directly;
            // exit foreground first, then re-enter as MICROPHONE now that RECORD_AUDIO is granted.
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                stopForeground(STOP_FOREGROUND_DETACH)
            }
            if (camera && startForegroundWithCamera(notification)) return true
            startForegroundWithMicrophone(notification)
            return !camera
        }

        override fun showOngoing(remoteName: String, video: Boolean) {
            val notifications = notificationManager!!
            val notification = notifications.buildOngoingCallNotification(remoteName, video)
            typedNotification = notification
            notifications.updateNotification(notification, CallNotificationManager.NOTIFICATION_ID_ONGOING)
        }

        override fun setForegroundCamera(camera: Boolean): Boolean {
            // Only a call past its incoming ring has a type to change. The session asks for no other.
            val notification = typedNotification ?: return true
            if (camera) return startForegroundWithCamera(notification)
            try {
                startForegroundWithMicrophone(notification)
            } catch (e: RuntimeException) {
                // The service keeps the type it had. A camera type left over allows nothing: the
                // camera is off.
                Log.w(TAG, "Could not drop the camera foreground type", e)
            }
            return true
        }

        override fun createLocalMedia(listener: CallLocalMedia.Listener): CallLocalMedia =
            WebRtcCallLocalMedia(applicationContext, callVideoSinks, listener, appScope)

        override fun startAudioSession() = this@CallService.startAudioSession()

        override fun setAudioFollowsVideo(preferSpeaker: Boolean, videoShowing: Boolean) {
            this@CallService.preferSpeaker = preferSpeaker
            this@CallService.videoShowing = videoShowing
            applyAudioFollowsVideo()
        }

        override fun stopAudioSession() = this@CallService.stopAudioSession()

        override fun onSessionFinished(session: CallSession) {
            if (this@CallService.session === session) this@CallService.session = null
            typedNotification = null
            notificationManager?.cancelNotification(CallNotificationManager.NOTIFICATION_ID_INCOMING)
            stopForeground(STOP_FOREGROUND_REMOVE)
            // The id, not stopSelf(): that stops the service even with the next call's start still
            // queued. The start would then run on a dying service that cannot ring, and its call
            // state would stay ongoing until the process died.
            stopSelf(lastStartId)
        }
    }

    private fun isGranted(permission: String): Boolean =
        checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED

    private fun startForegroundWithMicrophone(notification: Notification) = startForeground(
        CallNotificationManager.NOTIFICATION_ID_ONGOING,
        notification,
        ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
    )

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
    // Audio Session — focus, mode, routing, proximity
    // ──────────────────────────────────────────────────────────────────────────

    /**
     * Take audio focus, switch the device into communication mode, and start routing. Idempotent:
     * a connection reports connected again after every reconnect, and a second run would both
     * overwrite [previousAudioMode] with `MODE_IN_COMMUNICATION` and leak a second router and
     * collector.
     */
    private fun startAudioSession() {
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
        // Before the first routing, so a call with video starts on the speaker.
        applyAudioFollowsVideo()
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

    /** Does nothing before the audio session has started. [startAudioSession] applies it then. */
    private fun applyAudioFollowsVideo() {
        audioRouter?.setPreferSpeaker(preferSpeaker)
        proximityLock?.setVideoShowing(videoShowing)
    }

    /** Undo [startAudioSession], in the reverse order: proximity, routing, focus, mode. */
    private fun stopAudioSession() {
        // The next call starts as a voice call until its session says otherwise.
        preferSpeaker = false
        videoShowing = false
        // Latches before routeJob is cancelled, so no route report can re-acquire the lock after
        // the call is gone.
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

    override fun onDestroy() {
        super.onDestroy()
        session?.finish()
        serviceScope.cancel()
    }

    /** Stop the service unless it holds a call. A start that is still queued keeps it running. */
    private fun stopIfIdle(): Int {
        if (session == null) stopSelf(lastStartId)
        return START_NOT_STICKY
    }
}
