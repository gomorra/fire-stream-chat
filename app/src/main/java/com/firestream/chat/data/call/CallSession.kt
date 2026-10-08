package com.firestream.chat.data.call

import android.util.Log
import com.firestream.chat.domain.model.CallSignalingData
import com.firestream.chat.domain.model.CallState
import com.firestream.chat.domain.model.EndReason
import com.firestream.chat.domain.model.IceCandidateData
import com.firestream.chat.domain.repository.CallRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

/**
 * One call, from its first ring to its end: its signalling, its timer, and the states it publishes.
 * [CallService] makes one per call and passes it the user's actions. The session drives [CallHost]
 * for everything Android does, and [CallMedia] for the connection.
 *
 * Threading: confined to the main thread, like [CallService]. [CallMedia] delivers its callbacks
 * there, and the parent scope must dispatch there. The fields need no locks.
 *
 * A finished session ignores everything. Each entry point checks [isFinished] first, so a late
 * callback, snapshot or timer cannot touch the next call.
 */
internal class CallSession(
    val callId: String,
    /** Whether this phone placed the call. Only the caller writes the call's message to the chat. */
    val isCaller: Boolean,
    private val chatId: String?,
    private val remoteUserId: String,
    private val remoteName: String,
    private val remoteAvatarUrl: String?,
    private val repository: CallRepository,
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

    private var media: CallMedia? = null
    private var connectedAt: Long? = null
    private val processedIceCandidates = mutableSetOf<String>()

    /** Whether the callee's answer has been applied. A later snapshot that still says "answered" changes nothing. */
    private var answerApplied = false

    /** Ends the call if its current phase lasts too long: ringing, connecting, or a lost connection. */
    private var timer: Job? = null
    private var signaling: Job? = null
    private var iceCandidates: Job? = null

    var isFinished = false
        private set

    // ──────────────────────────────────────────────────────────────────────────
    // Outgoing
    // ──────────────────────────────────────────────────────────────────────────

    /** Publish the call, take the foreground and send the offer. The callee's phone is ringing. */
    fun startOutgoing() {
        stateHolder.updateState(
            CallState.OutgoingRinging(callId, remoteUserId, remoteName, remoteAvatarUrl, host.localAvatarPath(remoteUserId))
        )
        host.foregroundOutgoing(remoteName)
        startMedia().createLocalOffer(
            onCreated = { sdp -> scope.launch { repository.sendOffer(callId, sdp) } },
            onFailure = { error ->
                Log.e(TAG, "Failed to create offer: $error")
                end(EndReason.ERROR)
            }
        )
        observeCallDocument()
        startTimer(RING_TIMEOUT_MS, EndReason.TIMEOUT)
    }

    private fun onAnswered(data: CallSignalingData) {
        if (answerApplied) return
        startTimer(CONNECT_TIMEOUT_MS, EndReason.ERROR)
        stateHolder.updateState(connecting())
        host.showOngoing(remoteName)

        // The callee writes the answer and the status together, so this is only a guard.
        val answer = data.answer ?: run {
            Log.e(TAG, "Call answered but no answer SDP found — waiting for next snapshot")
            return
        }
        answerApplied = true
        media?.setRemoteDescription(
            answer,
            onSet = { observeIceCandidates(CALLEE_CANDIDATES) },
            onFailure = { error ->
                Log.e(TAG, "Failed to set remote description (caller): $error")
                end(EndReason.ERROR)
            }
        )
    }

    // ──────────────────────────────────────────────────────────────────────────
    // Incoming
    // ──────────────────────────────────────────────────────────────────────────

    /** Publish the call and take the foreground. The phone rings until it is answered or declined. */
    fun startIncoming() {
        stateHolder.updateState(
            CallState.IncomingRinging(callId, remoteUserId, remoteName, remoteAvatarUrl, host.localAvatarPath(remoteUserId))
        )
        host.foregroundIncoming(remoteName)
        observeCallDocument()
        startTimer(RING_TIMEOUT_MS, EndReason.TIMEOUT)
    }

    /**
     * Answer the ringing call. Answers once: a double tap, or the notification's Answer racing the
     * screen's, would otherwise apply the offer twice and send a second answer.
     */
    fun answer() {
        if (isFinished || stateHolder.callState.value !is CallState.IncomingRinging) return
        // CallActivity asks for the microphone before it sends this. Without it, the switch to a
        // microphone foreground service throws on Android 14+ and takes the app down.
        if (!host.hasMicrophonePermission()) {
            Log.w(TAG, "Not answering call $callId: RECORD_AUDIO is not granted")
            return
        }

        startTimer(CONNECT_TIMEOUT_MS, EndReason.ERROR)
        stateHolder.updateState(connecting())
        host.foregroundOngoing(remoteName)
        val media = startMedia()

        // WebRTC needs the offer applied before the answer is created or any candidate is added.
        scope.launch {
            repository.getCallById(callId)
                .onSuccess { data ->
                    if (isFinished) return@onSuccess
                    if (data.status != "ringing") {
                        // The caller hung up, or the ring timed out, before this phone heard about
                        // it. Answering now would overwrite "ended" with "answered" and connect to
                        // no one.
                        stateHolder.updateState(CallState.Ended(callId, EndReason.REMOTE_HANGUP))
                        finish()
                        return@onSuccess
                    }
                    val offer = data.offer
                    if (offer == null) {
                        Log.e(TAG, "No offer found in call document")
                        end(EndReason.ERROR)
                        return@onSuccess
                    }
                    media.setRemoteDescription(
                        offer,
                        onSet = {
                            media.createLocalAnswer(
                                // The answer and "answered" go in one write, so the caller always
                                // finds the answer when it sees the status.
                                onCreated = { sdp -> scope.launch { repository.sendAnswerAndAccept(callId, sdp) } },
                                onFailure = { error ->
                                    Log.e(TAG, "Failed to create answer: $error")
                                    end(EndReason.ERROR)
                                }
                            )
                            observeIceCandidates(CALLER_CANDIDATES)
                        },
                        onFailure = { error ->
                            Log.e(TAG, "Failed to set remote description (callee): $error")
                            end(EndReason.ERROR)
                        }
                    )
                }
                .onFailure { e ->
                    if (isFinished) return@onFailure
                    Log.e(TAG, "Failed to get call document", e)
                    end(EndReason.ERROR)
                }
        }
    }

    fun decline() {
        if (isFinished) return
        appScope.launch { repository.declineCall(callId) }
        stateHolder.updateState(CallState.Ended(callId, EndReason.DECLINED))
        finish()
    }

    // ──────────────────────────────────────────────────────────────────────────
    // Controls
    // ──────────────────────────────────────────────────────────────────────────

    fun hangup() = end(EndReason.HANGUP)

    /** Flip mute, and give the microphone exactly the value the UI shows. */
    fun toggleMute() {
        if (isFinished) return
        val muted = stateHolder.toggleMute()
        media?.setMicrophoneEnabled(!muted)
    }

    // ──────────────────────────────────────────────────────────────────────────
    // Signalling
    // ──────────────────────────────────────────────────────────────────────────

    private fun observeCallDocument() {
        signaling?.cancel()
        signaling = scope.launch {
            repository.observeCallDocument(callId)
                .catch { e -> Log.e(TAG, "Signaling listener error", e) }
                .collectLatest { onCallDocument(it) }
        }
    }

    private fun onCallDocument(data: CallSignalingData) {
        if (isFinished) return
        when (data.status) {
            "answered" -> if (isCaller) onAnswered(data)
            "declined" -> if (isCaller) {
                writeCallMessage(EndReason.DECLINED)
                stateHolder.updateState(CallState.Ended(callId, EndReason.DECLINED))
                finish()
            }
            "ended" -> {
                // The other phone ends a call nobody answered with "timeout" when its own ring
                // timeout fires first. The call then went unanswered, not hung up.
                val reason = if (data.endReason == TIMEOUT_REASON) EndReason.TIMEOUT else EndReason.REMOTE_HANGUP
                writeCallMessage(reason)
                stateHolder.updateState(CallState.Ended(callId, reason))
                finish()
            }
        }
    }

    private fun observeIceCandidates(subcollection: String) {
        iceCandidates?.cancel()
        iceCandidates = scope.launch {
            repository.observeIceCandidates(callId, subcollection)
                .catch { e -> Log.e(TAG, "ICE candidate listener error", e) }
                .collectLatest { candidates ->
                    // Each snapshot lists every candidate so far. Each is added once.
                    for (candidate in candidates) {
                        val key = "${candidate.sdpMid}:${candidate.sdpMLineIndex}:${candidate.sdp}"
                        if (processedIceCandidates.add(key)) media?.addIceCandidate(candidate)
                    }
                }
        }
    }

    // ──────────────────────────────────────────────────────────────────────────
    // Connection
    // ──────────────────────────────────────────────────────────────────────────

    /** Create the connection. Its microphone starts with the mute value the UI shows. */
    private fun startMedia(): CallMedia =
        host.createMedia(callId, mediaListener).also {
            media = it
            it.setMicrophoneEnabled(!stateHolder.uiControls.value.isMuted)
        }

    private val mediaListener = object : CallMedia.Listener {
        override fun onLocalIceCandidate(candidate: IceCandidateData) {
            if (isFinished) return
            scope.launch { repository.sendIceCandidate(callId, isCaller, candidate) }
        }

        override fun onConnectionChange(change: ConnectionChange) {
            if (isFinished) return
            when (change) {
                ConnectionChange.CONNECTED -> onConnected()
                ConnectionChange.DISCONNECTED -> {
                    // With continual gathering ICE may never report FAILED, so a connection that
                    // stays lost would hold the call open for good.
                    Log.w(TAG, "ICE disconnected — may reconnect")
                    startTimer(RECONNECT_TIMEOUT_MS, EndReason.ERROR)
                }
                ConnectionChange.FAILED -> {
                    Log.e(TAG, "ICE connection failed")
                    end(EndReason.ERROR)
                }
            }
        }
    }

    private fun onConnected() {
        timer?.cancel()
        // ICE reports CONNECTED and then COMPLETED, and again after every reconnect. The call's
        // clock starts at the first report only, or the timer would restart at 0:00.
        val startTime = connectedAt ?: clock().also { connectedAt = it }
        // The proximity lock is not taken here: it follows the audio route, which the audio
        // session applies as soon as the OS reports one.
        host.startAudioSession()
        stateHolder.updateState(
            CallState.Connected(callId, remoteUserId, remoteName, remoteAvatarUrl, startTime, host.localAvatarPath(remoteUserId))
        )
    }

    private fun connecting() =
        CallState.Connecting(callId, remoteUserId, remoteName, remoteAvatarUrl, host.localAvatarPath(remoteUserId))

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
    // End
    // ──────────────────────────────────────────────────────────────────────────

    /** End the call for [reason]: tell the other phone, record the call in the chat, publish the end, finish. */
    fun end(reason: EndReason) {
        if (isFinished) return
        appScope.launch { repository.endCall(callId, reason.name.lowercase()) }
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
        appScope.launch { repository.logCallMessage(chatId, reason.name.lowercase(), durationSeconds) }
    }

    /**
     * Release what the call holds, in order: its jobs, its connection, its audio session, and last
     * its hold on the service. Publishes nothing, because whoever ends the call publishes the end.
     * Idempotent.
     */
    fun finish() {
        if (isFinished) return
        isFinished = true
        scope.cancel()
        media?.dispose()
        media = null
        host.stopAudioSession()
        host.onSessionFinished(this)
    }

    companion object {
        private const val TAG = "CallSession"
        /** How long a call rings before it ends as unanswered. */
        const val RING_TIMEOUT_MS = 30_000L

        /** How long an answered call may take to connect. */
        const val CONNECT_TIMEOUT_MS = 30_000L

        /** How long a lost connection may take to come back. */
        const val RECONNECT_TIMEOUT_MS = 30_000L

        private val TIMEOUT_REASON = EndReason.TIMEOUT.name.lowercase()
        private const val CALLER_CANDIDATES = "callerCandidates"
        private const val CALLEE_CANDIDATES = "calleeCandidates"
    }
}
