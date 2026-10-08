package com.firestream.chat.data.call

import android.Manifest
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
 * the foreground notification, the audio session and the WebRTC objects. Each call itself runs in
 * a [CallSession], which reaches those through [host].
 *
 * Threading: everything runs on the main thread. Intents and lifecycle callbacks arrive there,
 * [serviceScope] dispatches there, and [WebRtcCallMedia] posts WebRTC's signaling-thread callbacks
 * there. The fields therefore need no locks, and teardown never races itself. Teardown must never
 * run on the signaling thread: disposing the factory frees that very thread.
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

        const val EXTRA_CALL_ID = "call_id"
        const val EXTRA_CHAT_ID = "chat_id"
        const val EXTRA_REMOTE_USER_ID = "remote_user_id"
        const val EXTRA_REMOTE_NAME = "remote_name"
        const val EXTRA_REMOTE_AVATAR_URL = "remote_avatar_url"
        const val EXTRA_AUDIO_ROUTE = "audio_route"

        private const val TAG = "CallService"

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

    /**
     * For the writes that record how a call ended. They must outlive the service: a call that
     * ends stops the service right after launching them, and [onDestroy] cancels [serviceScope].
     */
    @Inject @ApplicationScope lateinit var appScope: CoroutineScope

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val mainHandler = Handler(Looper.getMainLooper())

    private var notificationManager: CallNotificationManager? = null

    /** The call this service holds. One at a time. */
    private var session: CallSession? = null

    private var routeJob: Job? = null
    private var audioManager: AudioManager? = null
    private var audioFocusRequest: AudioFocusRequest? = null
    private var audioRouter: CallAudioRouter? = null
    private var proximityLock: ProximityLock? = null

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
                startOutgoingCall(callId, chatId, userId, name, avatar)
            }
            ACTION_START_INCOMING -> {
                val callId = intent.getStringExtra(EXTRA_CALL_ID) ?: return stopIfIdle()
                val userId = intent.getStringExtra(EXTRA_REMOTE_USER_ID) ?: return stopIfIdle()
                val name = intent.getStringExtra(EXTRA_REMOTE_NAME) ?: "Unknown"
                val avatar = intent.getStringExtra(EXTRA_REMOTE_AVATAR_URL)
                startIncomingCall(callId, userId, name, avatar)
            }
            ACTION_ANSWER -> session?.answer()
            ACTION_DECLINE -> decline(intent.getStringExtra(EXTRA_CALL_ID))
            ACTION_HANGUP -> session?.hangup()
            ACTION_TOGGLE_MUTE -> session?.toggleMute()
            ACTION_SELECT_AUDIO_ROUTE -> selectAudioRoute(intent.getStringExtra(EXTRA_AUDIO_ROUTE))
        }
        // An action that finds no call, such as a second tap on Hang Up, must not leave the
        // service running.
        return stopIfIdle()
    }

    private fun startOutgoingCall(callId: String, chatId: String, userId: String, name: String, avatar: String?) {
        session?.let { current ->
            // Another call holds the service. Its connection must not be reused for this one.
            // The new call's document already exists and has rung the callee, so end it rather
            // than leave their phone ringing.
            Log.w(TAG, "Ending outgoing call $callId: call ${current.callId} is in progress")
            appScope.launch { callRepository.endCall(callId, EndReason.HANGUP) }
            return
        }
        // Held before it starts: a session that ends while starting must find itself here to let go.
        val call = newSession(callId, isCaller = true, chatId, userId, name, avatar)
        session = call
        call.startOutgoing()
    }

    private fun startIncomingCall(callId: String, userId: String, name: String, avatar: String?) {
        session?.let { current ->
            // FCMService checks this too, but a call can start between its check and this intent.
            Log.w(TAG, "Ignoring incoming call $callId: call ${current.callId} is in progress")
            return
        }
        val call = newSession(callId, isCaller = false, chatId = null, userId, name, avatar)
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
    ) = CallSession(
        callId = callId,
        isCaller = isCaller,
        chatId = chatId,
        remoteUserId = userId,
        remoteName = name,
        remoteAvatarUrl = avatar,
        repository = callRepository,
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
        override fun hasMicrophonePermission(): Boolean =
            checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

        override fun localAvatarPath(userId: String): String? {
            if (userId.isEmpty()) return null
            val file = profileImageManager.getLocalFile(userId)
            return if (file.exists()) file.absolutePath else null
        }

        override fun foregroundOutgoing(remoteName: String) {
            startForeground(
                CallNotificationManager.NOTIFICATION_ID_ONGOING,
                notificationManager!!.buildOutgoingCallNotification(remoteName),
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
            )
        }

        override fun foregroundIncoming(remoteName: String) {
            val notification = notificationManager!!.buildIncomingCallNotification(remoteName)
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

        override fun foregroundOngoing(remoteName: String) {
            val notification = notificationManager!!.buildOngoingCallNotification(remoteName)
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
        }

        override fun showOngoing(remoteName: String) {
            val notifications = notificationManager!!
            notifications.updateNotification(
                notifications.buildOngoingCallNotification(remoteName),
                CallNotificationManager.NOTIFICATION_ID_ONGOING
            )
        }

        override fun createMedia(callId: String, listener: CallMedia.Listener): CallMedia =
            WebRtcCallMedia(applicationContext, mainHandler, listener)

        override fun startAudioSession() = this@CallService.startAudioSession()

        override fun stopAudioSession() = this@CallService.stopAudioSession()

        override fun onSessionFinished(session: CallSession) {
            if (this@CallService.session === session) this@CallService.session = null
            notificationManager?.cancelNotification(CallNotificationManager.NOTIFICATION_ID_INCOMING)
            stopForeground(STOP_FOREGROUND_REMOVE)
            // The id, not stopSelf(): that stops the service even with the next call's start still
            // queued. The start would then run on a dying service that cannot ring, and its call
            // state would stay ongoing until the process died.
            stopSelf(lastStartId)
        }
    }

    // ──────────────────────────────────────────────────────────────────────────
    // Audio Session — focus, mode, routing, proximity
    // ──────────────────────────────────────────────────────────────────────────

    /**
     * Take audio focus, switch the device into communication mode, and start routing. Idempotent:
     * ICE reports CONNECTED and then COMPLETED, and a second run would both overwrite
     * [previousAudioMode] with `MODE_IN_COMMUNICATION` and leak a second router and collector.
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
    private fun stopAudioSession() {
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
        mainHandler.removeCallbacksAndMessages(null)
    }

    /** Stop the service unless it holds a call. A start that is still queued keeps it running. */
    private fun stopIfIdle(): Int {
        if (session == null) stopSelf(lastStartId)
        return START_NOT_STICKY
    }
}
