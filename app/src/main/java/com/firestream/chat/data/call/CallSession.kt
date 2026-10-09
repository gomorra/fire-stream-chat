// region: AGENT-NOTE
// Responsibility: One call, from its first ring to its end — its states, its ring and its
//   timers, the status of the call document, the end reason, and the call's chat message.
// Owns: The `CallState` it publishes, the people and controls of the call in CallStateHolder,
//   the call's kind, the camera switch, and what this side says about itself on the call
//   document. It opens the connection to the other person and reacts to its events.
// Collaborators: CallHost (everything Android does; CallService provides it), CallLocalMedia
//   (microphone, camera, connections), PeerSignaling / OneToOneSignaling (offer, answer and
//   candidates), CameraSwitch, CallMediaPublisher, CallRepository, IceServerProvider.
// Don't put here: A WebRTC type or a lock. Everything runs on the main thread, and
//   CallLocalMedia delivers its callbacks there. No Android class beyond `Log` either: CallHost
//   is the seam that keeps this class testable on the JVM.
// endregion

package com.firestream.chat.data.call

import android.util.Log
import com.firestream.chat.domain.model.CallMedia
import com.firestream.chat.domain.model.CallParticipant
import com.firestream.chat.domain.model.CallSignalingData
import com.firestream.chat.domain.model.CallState
import com.firestream.chat.domain.model.EndReason
import com.firestream.chat.domain.model.IceServerData
import com.firestream.chat.domain.model.SdpData
import com.firestream.chat.domain.repository.CallRepository
import com.firestream.chat.domain.repository.ChatRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

/**
 * One call, from its first ring to its end: its signalling, its timer, and the states it publishes.
 * [CallService] makes one per call and passes it the user's actions. The session drives [CallHost]
 * for everything Android does, and [CallLocalMedia] for the microphone, the camera and the
 * connection to the other person.
 *
 * The connection negotiates by itself: a [PeerSession] exchanges the offer, the answer and the
 * candidates over a [PeerSignaling]. The session owns what the call document says beyond that: its
 * status, how the call was started, and each side's camera and microphone.
 *
 * Threading: confined to the main thread, like [CallService]. [CallLocalMedia] delivers its
 * callbacks there, and the parent scope must dispatch there. The fields need no locks.
 *
 * A finished session ignores everything. Each entry point checks [isFinished] first, so a late
 * callback, snapshot or timer cannot touch the next call.
 */
internal class CallSession(
    val callId: String,
    /** Whether this phone placed the call. Only the caller writes the call's message to the chat. */
    val isCaller: Boolean,
    /** The call's chat. The caller knows it. The side that answers looks it up, for the dock only. */
    private val chatId: String?,
    private val remoteUserId: String,
    private val remoteName: String,
    private val remoteAvatarUrl: String?,
    /** How the call was started. The side that is called can learn it late, from the call document. */
    video: Boolean,
    /**
     * Caller only: the callee's app takes a video line, so the offer carries one. Never true on a
     * guess, because an app without video crashes on such an offer.
     */
    private val offerVideoLine: Boolean,
    private val repository: CallRepository,
    private val chatRepository: ChatRepository,
    private val iceServers: IceServerProvider,
    private val stateHolder: CallStateHolder,
    private val host: CallHost,
    parentScope: CoroutineScope,
    /** For the writes that record how the call ended. They must outlive the session. */
    private val appScope: CoroutineScope,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    /** Cancelled when the call finishes, with its timer, its listeners and its pending sends. */
    private val scope = CoroutineScope(
        parentScope.coroutineContext + SupervisorJob(parentScope.coroutineContext[Job])
    )

    /** The one store of the call's kind. The states and the notifications are built from it. */
    private var video = video

    /** Made on first use: an incoming call rings without it, unless it shows a camera preview. */
    private var localMedia: CallLocalMedia? = null
    private var connectedAt: Long? = null

    /** The camera captures right now. It lags behind [CameraSwitch.isRunning] while the camera opens. */
    private var cameraCapturing = false

    /** Ends the call if its current phase lasts too long: ringing, connecting, or a lost connection. */
    private var timer: Job? = null

    /** What this side says on the call document, and whether it may say anything there. */
    private val mediaPublisher = CallMediaPublisher(repository, scope)

    private val cameraSwitch = CameraSwitch(object : CameraSwitch.Port {
        override fun hasCameraPermission() = host.hasCameraPermission()

        // An incoming ring is a short service, which takes no type. Answering sets the type.
        override fun setForegroundCamera(camera: Boolean) =
            stateHolder.callState.value is CallState.IncomingRinging || host.setForegroundCamera(camera)

        override fun setCameraRunning(running: Boolean) {
            if (running) {
                localMedia().setCameraRunning(true)
                return
            }
            localMedia?.setCameraRunning(false)
            onCameraCapturing(false)
        }

        override fun onSwitchChanged(on: Boolean, paused: Boolean) =
            stateHolder.updateControls { it.copy(cameraOn = on, cameraPaused = paused) }
    })

    var isFinished = false
        private set

    // ──────────────────────────────────────────────────────────────────────────
    // Outgoing
    // ──────────────────────────────────────────────────────────────────────────

    /**
     * Take the call over from its placing, take the foreground and open the connection, which
     * sends the offer. The callee's phone is already ringing, and answers by fetching the offer
     * once, so nothing waits here: `CallRepository.createCall` fetched the relay's servers before
     * the call existed.
     *
     * A call whose placing was cancelled or timed out first is ended instead. The foreground comes
     * first all the same: once started with startForegroundService(), the service must enter it.
     */
    fun startOutgoing() {
        host.foregroundOutgoing(remoteName, video)
        val localAvatar = host.localAvatarPath(remoteUserId)
        val ringing = CallState.OutgoingRinging(callId, remoteUserId, remoteName, remoteAvatarUrl, localAvatar, video)
        if (!stateHolder.takeOverPlacing(ringing)) {
            Log.w(TAG, "Ending outgoing call $callId: it is no longer being placed")
            appScope.launch { repository.endCall(callId, EndReason.HANGUP) }
            finish()
            return
        }
        begin(localAvatar)
        // Known from the start, so no preview runs for a call that cannot send it.
        if (!offerVideoLine) onVideoLineKnown(false)
        openPeer(iceServers.current(), offer = null)
        observeCallDocument()
        watchScreens()
        startTimer(RING_TIMEOUT_MS, EndReason.TIMEOUT)
    }

    private fun onAnswered() {
        // Once: a later snapshot that still says "answered" finds the call past its ring. The
        // connection watches the same document and applies the answer itself, so it can report
        // connected before this snapshot arrives, and a connected call is not taken back.
        if (stateHolder.callState.value !is CallState.OutgoingRinging) return
        startTimer(CONNECT_TIMEOUT_MS, EndReason.ERROR)
        stateHolder.updateState(connecting())
        host.showOngoing(remoteName, video)
    }

    // ──────────────────────────────────────────────────────────────────────────
    // Incoming
    // ──────────────────────────────────────────────────────────────────────────

    /** Publish the call and take the foreground. The phone rings until it is answered or declined. */
    fun startIncoming() {
        val localAvatar = host.localAvatarPath(remoteUserId)
        begin(localAvatar)
        stateHolder.updateState(
            CallState.IncomingRinging(callId, remoteUserId, remoteName, remoteAvatarUrl, localAvatar, video)
        )
        host.foregroundIncoming(remoteName, video)
        // Fetched while it rings, so the answer does not wait for the relay's servers.
        iceServers.warm()
        observeCallDocument()
        watchScreens()
        startTimer(RING_TIMEOUT_MS, EndReason.TIMEOUT)
    }

    /**
     * Answer the ringing call. Answers once: a double tap, or the notification's Answer racing the
     * screen's, would otherwise open a second connection and send a second answer.
     *
     * @param camera answer with the own camera on. False switches a preview off that ran while the
     *   call rang. True still needs the `CAMERA` permission, which the screen asked for.
     */
    fun answer(camera: Boolean) {
        if (isFinished || stateHolder.callState.value !is CallState.IncomingRinging) return
        // CallActivity asks for the microphone before it sends this. Without it, the switch to a
        // microphone foreground service throws on Android 14+ and takes the app down.
        if (!host.hasMicrophonePermission()) {
            Log.w(TAG, "Not answering call $callId: RECORD_AUDIO is not granted")
            return
        }

        // While the call still rings: the ring is a short service, and the type is set below.
        cameraSwitch.set(camera)
        startTimer(CONNECT_TIMEOUT_MS, EndReason.ERROR)
        stateHolder.updateState(connecting())
        if (!host.foregroundOngoing(remoteName, video, camera = cameraSwitch.isOn)) cameraSwitch.onForegroundRefused()
        resolveChat()

        scope.launch {
            // The ring fetched the relay's servers, so they are normally here. The caller waits
            // for the answer, so this side may wait for them, beside the read below.
            val servers = async { iceServers.get() }
            val data = repository.getCallById(callId).getOrElse { e ->
                Log.e(TAG, "Failed to get call document", e)
                return@launch end(EndReason.ERROR)
            }
            // The caller hung up, or the ring timed out, before this phone heard about it.
            // Answering now would overwrite "ended" with "answered" and connect to no one.
            if (data.status != "ringing") return@launch close(EndReason.REMOTE_HANGUP)
            val offer = data.offer
            if (offer == null) {
                Log.e(TAG, "No offer found in call document")
                return@launch end(EndReason.ERROR)
            }
            // The connection applies the offer, and writes its answer and "answered" in one
            // write, so the caller always finds the answer when it sees the status.
            openPeer(servers.await(), offer)
        }
        // Built while the call document is read, unless that read has already ended the call.
        if (!isFinished) localMedia()
    }

    /**
     * The side that answers learns the call's chat here, so the call can dock over it. Only the
     * holder is told. [chatId] stays unset on this side: it is what makes the caller, and only the
     * caller, write the call's message.
     */
    private fun resolveChat() {
        scope.launch {
            val chat = chatRepository.getOrCreateChat(remoteUserId)
            // The lookup turns a cancellation into a failure, so a call that ended meanwhile ends here.
            if (isFinished) return@launch
            chat.onSuccess { stateHolder.setChatId(callId, it.id) }
                .onFailure { e -> Log.w(TAG, "The call's chat is not known, so it cannot dock", e) }
        }
    }

    fun decline() {
        if (isFinished) return
        appScope.launch { repository.declineCall(callId) }
        close(EndReason.DECLINED)
    }

    // ──────────────────────────────────────────────────────────────────────────
    // Controls
    // ──────────────────────────────────────────────────────────────────────────

    fun hangup() = end(EndReason.HANGUP)

    /** Flip mute, and give the microphone exactly the value the UI shows. */
    fun toggleMute() {
        if (isFinished) return
        val muted = stateHolder.toggleMute()
        localMedia?.setMicrophoneEnabled(!muted)
        mediaPublisher.update { it.copy(mic = !muted) }
    }

    /**
     * The user switched the own camera. It goes on only with the `CAMERA` permission, which the
     * session never asks for, and only while the other side takes video. See [CameraSwitch].
     */
    fun setCamera(on: Boolean) {
        if (isFinished) return
        cameraSwitch.set(on)
    }

    fun flipCamera() {
        if (isFinished) return
        localMedia?.flipCamera()
    }

    // ──────────────────────────────────────────────────────────────────────────
    // The call document
    // ──────────────────────────────────────────────────────────────────────────

    private fun observeCallDocument() {
        scope.launch {
            repository.observeCallDocument(callId)
                .catch { e -> Log.e(TAG, "Signaling listener error", e) }
                .collectLatest { onCallDocument(it) }
        }
    }

    /** Runs on every snapshot. The document changes with each camera and microphone switch of either side. */
    private fun onCallDocument(data: CallSignalingData) {
        if (isFinished) return
        if (data.video) onStartedAsVideo()
        onRemoteMedia(data.media[remoteUserId] ?: CallMedia())
        when (data.status) {
            "answered" -> if (isCaller) onAnswered()
            "declined" -> if (isCaller) close(EndReason.DECLINED)
            "ended" -> {
                // The other phone ends a call nobody answered with "timeout" when its own ring
                // timeout fires first. The call then went unanswered, not hung up.
                val timedOut = EndReason.fromWireName(data.endReason) == EndReason.TIMEOUT
                close(if (timedOut) EndReason.TIMEOUT else EndReason.REMOTE_HANGUP)
            }
        }
    }

    /**
     * The call document says the call was started as video. The push normally says so first. A
     * function deployed before the kind existed does not, and then the ring starts as a voice call
     * until this arrives.
     */
    private fun onStartedAsVideo() {
        if (video) return
        video = true
        if (stateHolder.markVideo(callId) is CallState.IncomingRinging) host.showIncoming(remoteName, video = true)
        followVideoWithAudio()
    }

    /**
     * What the other side says about their camera and microphone. Someone who has written nothing
     * reads as camera off, microphone on.
     */
    private fun onRemoteMedia(media: CallMedia) {
        val known = stateHolder.participants.value.firstOrNull { it.id == remoteUserId } ?: return
        if (known.cameraOn == media.camera && known.micOn == media.mic) return
        // Their track stays the same through a switch, and a view keeps the last picture it drew.
        // The video shows again only with a frame that arrived after the switch.
        if (known.cameraOn != media.camera) localMedia?.awaitFrame(remoteUserId)
        stateHolder.updateParticipant(remoteUserId) { it.copy(cameraOn = media.camera, micOn = media.mic) }
        followVideoWithAudio()
    }

    // ──────────────────────────────────────────────────────────────────────────
    // Connection
    // ──────────────────────────────────────────────────────────────────────────

    /** The call's media, made on first use. Its microphone starts with the mute value the UI shows. */
    private fun localMedia(): CallLocalMedia = localMedia ?: host.createLocalMedia(mediaListener).also { media ->
        localMedia = media
        media.setMicrophoneEnabled(!stateHolder.uiControls.value.isMuted)
        scope.launch { media.framed.collect { stateHolder.setFramed(it) } }
    }

    /**
     * Open the connection to the other person and start negotiating. In a 1:1 call the caller
     * makes the offer.
     *
     * @param offer the offer this side answers, as read from the call document. Null for the caller.
     */
    private fun openPeer(servers: List<IceServerData>, offer: SdpData?) {
        val events = localMedia().openPeer(
            remoteId = remoteUserId,
            signaling = OneToOneSignaling(repository, callId, isCaller, offer),
            offers = isCaller,
            offerVideoLine = offerVideoLine,
            iceServers = servers,
        )
        scope.launch { events.collect { onPeerEvent(it) } }
    }

    private fun onPeerEvent(event: PeerSessionEvent) {
        if (isFinished) return
        when (event) {
            PeerSessionEvent.Connected -> onConnected()
            PeerSessionEvent.Disconnected -> {
                // With continual gathering ICE may never report FAILED, so a connection that
                // stays lost would hold the call open for good.
                Log.w(TAG, "Connection lost — may reconnect")
                stateHolder.updateParticipant(remoteUserId) { it.copy(connected = false) }
                startTimer(RECONNECT_TIMEOUT_MS, EndReason.ERROR)
            }
            is PeerSessionEvent.Failed -> {
                Log.e(TAG, "Connection failed: ${event.reason}")
                end(EndReason.ERROR)
            }
            is PeerSessionEvent.VideoLine -> onVideoLineKnown(event.available)
            // The local media gives the track to the video views.
            is PeerSessionEvent.RemoteTrack -> Unit
        }
    }

    private fun onConnected() {
        timer?.cancel()
        // The connection reports connected again after every reconnect. The call's clock starts
        // at the first report only, or the timer would restart at 0:00.
        val startTime = connectedAt ?: clock().also { connectedAt = it }
        // Connected before the "answered" snapshot arrived here.
        if (stateHolder.callState.value is CallState.OutgoingRinging) host.showOngoing(remoteName, video)
        // Before the first routing, so a call with video starts on the speaker. The proximity
        // lock is not taken here: it follows the audio route, which the audio session applies as
        // soon as the OS reports one.
        followVideoWithAudio()
        host.startAudioSession()
        mediaPublisher.onConnected(callId)
        stateHolder.updateParticipant(remoteUserId) { it.copy(connected = true) }
        stateHolder.updateState(
            CallState.Connected(callId, remoteUserId, remoteName, remoteAvatarUrl, startTime, host.localAvatarPath(remoteUserId), video)
        )
    }

    /** The call has a video line both sides agreed on, or it is known to have none. */
    private fun onVideoLineKnown(available: Boolean) {
        stateHolder.updateControls { it.copy(videoAvailable = available) }
        // Only a call with an agreed video line is ever written to, see CallMediaPublisher.
        mediaPublisher.onVideoLine(callId, available)
        cameraSwitch.onVideoLine(available)
        // Without a video line this is a voice call on screen, and leaves the speaker default.
        followVideoWithAudio()
    }

    private fun connecting() =
        CallState.Connecting(callId, remoteUserId, remoteName, remoteAvatarUrl, host.localAvatarPath(remoteUserId), video)

    // ──────────────────────────────────────────────────────────────────────────
    // Camera
    // ──────────────────────────────────────────────────────────────────────────

    private val mediaListener = object : CallLocalMedia.Listener {
        override fun onCameraStarted(front: Boolean) {
            // A stop can overtake the camera while it opens.
            if (isFinished || !cameraSwitch.isRunning) return
            stateHolder.updateControls { it.copy(frontCamera = front) }
            onCameraCapturing(true)
        }

        override fun onCameraFailed() {
            if (isFinished) return
            Log.w(TAG, "The camera stopped. The call goes on without it.")
            cameraSwitch.onCameraFailed()
        }

        override fun onCameraFlipped(front: Boolean) {
            if (isFinished) return
            stateHolder.updateControls { it.copy(frontCamera = front) }
        }
    }

    /**
     * Follow the screens that show the call: the stage and the docked card report themselves to
     * the holder. Started once the call's ringing state is published, because the first screen
     * decides the preview from it.
     */
    private fun watchScreens() {
        scope.launch { stateHolder.onScreen.collect { onScreen(it) } }
    }

    /**
     * A screen shows the call, or none has for a moment. A call started as video shows the own
     * camera while it rings. The ring never asks for the permission, and a locked phone shows no
     * preview.
     */
    private fun onScreen(showing: Boolean) {
        if (isFinished) return
        val preview = showing && video && when (stateHolder.callState.value) {
            is CallState.OutgoingRinging -> true
            is CallState.IncomingRinging -> !host.isLocked()
            else -> false
        }
        cameraSwitch.onScreen(showing, preview)
    }

    /** The camera started or stopped capturing. The other side is told, and the audio follows. */
    private fun onCameraCapturing(capturing: Boolean) {
        if (cameraCapturing == capturing) return
        cameraCapturing = capturing
        // While the screen is away the other side is told the camera is off, and shows the avatar.
        mediaPublisher.update { it.copy(camera = capturing) }
        followVideoWithAudio()
    }

    /**
     * Audio follows video. A call started as video that can carry video, or one where video shows
     * right now on either side, plays on the speaker unless a headset is connected or the user
     * picked a route. A call started as video to an app without video is a voice call on screen,
     * and starts on the earpiece. While video shows the proximity lock is off.
     */
    private fun followVideoWithAudio() {
        val showing = cameraCapturing || stateHolder.participants.value.any { it.cameraOn }
        val startedAsVideo = video && stateHolder.uiControls.value.videoAvailable
        host.setAudioFollowsVideo(preferSpeaker = startedAsVideo || showing, videoShowing = showing)
    }

    // ──────────────────────────────────────────────────────────────────────────
    // Timer
    // ──────────────────────────────────────────────────────────────────────────

    /** End the call for [reason] unless it moves on within [timeoutMs]. Replaces the previous phase's timer. */
    private fun startTimer(timeoutMs: Long, reason: EndReason) {
        timer?.cancel()
        timer = scope.launch {
            delay(timeoutMs)
            end(reason)
        }
    }

    // ──────────────────────────────────────────────────────────────────────────
    // Start and end
    // ──────────────────────────────────────────────────────────────────────────

    /**
     * Everything a call starts with, whichever side starts it: the other person, the chat if it
     * is known, and fresh controls.
     */
    private fun begin(localAvatar: String?) {
        stateHolder.beginCall(callId, listOf(CallParticipant(remoteUserId, remoteName, remoteAvatarUrl, localAvatar)), chatId)
        mediaPublisher.begin(callId)
    }

    /** End the call for [reason]: tell the other phone, then [close] it. */
    fun end(reason: EndReason) {
        if (isFinished) return
        appScope.launch { repository.endCall(callId, reason) }
        close(reason)
    }

    /** Record the call in the chat, publish its end for [reason], and finish. */
    private fun close(reason: EndReason) {
        writeCallMessage(reason)
        stateHolder.updateState(CallState.Ended(callId, reason))
        finish()
    }

    /**
     * Write the call's message to the chat. Only the caller does. Each path here finishes the
     * session, so it runs once.
     */
    private fun writeCallMessage(reason: EndReason) {
        if (!isCaller) return
        val chatId = chatId ?: return
        val durationSeconds = connectedAt?.let { ((clock() - it) / 1000).toInt() } ?: 0
        val video = video
        appScope.launch { repository.logCallMessage(chatId, reason, durationSeconds, video) }
    }

    /**
     * Release what the call holds, in order: its jobs, its media, its audio session, and last its
     * hold on the service. Publishes no state, because whoever ends the call publishes the end.
     * Idempotent.
     */
    fun finish() {
        if (isFinished) return
        isFinished = true
        scope.cancel()
        mediaPublisher.end()
        localMedia?.dispose()
        localMedia = null
        host.stopAudioSession()
        // The views are gone with the tracks, so no tile claims a picture any more.
        stateHolder.updateControls { it.copy(cameraOn = false, cameraPaused = false) }
        stateHolder.setFramed(emptySet())
        host.onSessionFinished(this)
    }

    companion object {
        private const val TAG = "CallSession"
        /** How long a call rings before it ends as unanswered. `functions/callPush.js` keeps the incoming-call push as long (`RING_TTL_MS`). */
        const val RING_TIMEOUT_MS = 30_000L

        /** How long an answered call may take to connect. */
        const val CONNECT_TIMEOUT_MS = 30_000L

        /** How long a lost connection may take to come back. */
        const val RECONNECT_TIMEOUT_MS = 30_000L
    }
}
