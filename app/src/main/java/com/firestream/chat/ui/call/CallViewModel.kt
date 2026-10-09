package com.firestream.chat.ui.call

import android.content.Context
import android.util.Log
import android.view.View
import androidx.lifecycle.ViewModel
import com.firestream.chat.data.call.CallService
import com.firestream.chat.data.call.CallStateHolder
import com.firestream.chat.data.call.CallVideoSinks
import com.firestream.chat.di.ApplicationScope
import com.firestream.chat.domain.model.CallAudioRoute
import com.firestream.chat.domain.model.CallParticipant
import com.firestream.chat.domain.model.CallState
import com.firestream.chat.domain.model.CallSurface
import com.firestream.chat.domain.model.CallUiControls
import com.firestream.chat.domain.model.EndReason
import com.firestream.chat.domain.repository.CallRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import javax.inject.Inject

/**
 * What both surfaces of a call read and send: the stage in `CallActivity`, and the docked card in a
 * chat. The stage's instance also places the outgoing call.
 */
@HiltViewModel
class CallViewModel @Inject constructor(
    private val callStateHolder: CallStateHolder,
    private val callRepository: CallRepository,
    private val videoSinks: CallVideoSinks,
    @ApplicationScope private val appScope: CoroutineScope,
    @ApplicationContext private val context: Context
) : ViewModel() {

    val callState: StateFlow<CallState> = callStateHolder.callState
    val uiControls: StateFlow<CallUiControls> = callStateHolder.uiControls
    val participants: StateFlow<List<CallParticipant>> = callStateHolder.participants

    /** The chat the call can dock over, or null while it is not known. */
    val callChatId: StateFlow<String?> = callStateHolder.chatId

    /** The surfaces that show the call right now. */
    val surfaces: StateFlow<Set<CallSurface>> = callStateHolder.surfaces

    /**
     * The action waiting on the permission prompt. It lives here, not in the activity, so that an
     * answer after a rotation still runs it.
     */
    internal var pendingPermissionAction: PermissionAction? = null

    private val _setupFailed = Channel<Unit>(Channel.CONFLATED)

    /** Emits when an outgoing call could not be created. */
    val setupFailed: Flow<Unit> = _setupFailed.receiveAsFlow()

    /** The call this screen placed last. Main thread only. */
    private var placed: PlacedCall? = null

    /** A placing as it started, and the chat it is in. */
    private class PlacedCall(val placing: CallState.Placing, val chatId: String)

    /**
     * Set when the call screen closes for good. A rotation keeps this ViewModel, so it is not a
     * close. Main thread only.
     */
    private var closed = false

    /** A call is about to be placed or answered: fetch ahead what its connection needs. */
    fun prepareCall() = callRepository.prepareCall()

    /**
     * Publish the call as being placed, then create it and hand it to [CallService]. Does nothing
     * while a call is ongoing, this screen's own placing included.
     *
     * The setup runs on the application scope. The call document rings the callee as soon as it
     * exists, so a setup cancelled halfway would leave them ringing. Each step is bounded, and a
     * failed step ends the placing before anything is written, so the placing never stays up to
     * block every later call.
     *
     * The setup touches only its own placing. A Cancel, or a closed screen, can end it at any
     * stage. Whoever ends a placing whose document exists also ends the call and records it,
     * because no service holds that call.
     *
     * @param video how the call is started: the camera icon, or the phone icon. Whether the call
     *   can carry video at all is found out when it is created.
     */
    fun placeCall(calleeId: String, chatId: String, calleeName: String, calleeAvatarUrl: String?, video: Boolean) {
        if (closed) return
        val placing = callStateHolder.startPlacing(calleeId, calleeName, calleeAvatarUrl, video) ?: return
        val placed = PlacedCall(placing, chatId).also { placed = it }
        appScope.launch { setUp(placed) }
    }

    private suspend fun setUp(placed: PlacedCall) {
        val placing = placed.placing
        val placingId = placing.placingId
        // The create goes on after a timeout, so a call it creates late is ended, not left ringing.
        val creating = appScope.async { callRepository.createCall(placing.calleeId, placing.video) }
        val created = withTimeoutOrNull(CREATE_TIMEOUT_MS) { creating.await() }
        if (created == null) {
            Log.w(TAG, "Creating the call took too long")
            val timedOut = callStateHolder.failPlacing(placingId)
            if (timedOut) _setupFailed.trySend(Unit)
            // A Cancel, or a closed screen, may have ended the placing before the timeout did.
            val reason = if (timedOut) EndReason.ERROR else EndReason.HANGUP
            creating.await().onSuccess { call -> endUnheldCall(call.callId, placed, reason) }
            return
        }
        val call = created.getOrElse { e ->
            Log.w(TAG, "Could not create the call", e)
            if (callStateHolder.failPlacing(placingId)) _setupFailed.trySend(Unit)
            return
        }
        val callId = call.callId
        // Cancel, or a closed screen, ended the placing while the document was created.
        if (!callStateHolder.placingCreated(placingId, callId)) {
            endUnheldCall(callId, placed, EndReason.HANGUP)
            return
        }
        try {
            CallService.startOutgoing(
                context, callId, placed.chatId, placing.calleeId, placing.calleeName, placing.calleeAvatarUrl,
                video = placing.video,
                // From createCall and never from a guess: an app without video crashes on an offer
                // with a video line.
                videoLine = call.videoLine
            )
        } catch (e: IllegalStateException) {
            // Android 12+ will not start a foreground service once the app is in the background,
            // and a slow setup can outlast the user leaving the app.
            Log.w(TAG, "Could not start the call service", e)
            if (callStateHolder.failPlacing(placingId)) endUnheldCall(callId, placed, EndReason.ERROR)
            return
        }
        // The service takes the call over on the main thread a moment later. If it never does, the
        // placing ends here, and the service, if it starts at all, finds it gone and ends the call.
        val handedOver = withTimeoutOrNull(HANDOVER_TIMEOUT_MS) {
            callStateHolder.callState.first { it !is CallState.Placing || it.placingId != placingId }
        }
        if (handedOver == null && callStateHolder.failPlacing(placingId)) {
            endUnheldCall(callId, placed, EndReason.ERROR)
        }
    }

    /**
     * End a call whose document exists but which no service holds, and record it in the chat. Its
     * push has already rung the callee, so they see a missed call and the caller sees no answer.
     */
    private suspend fun endUnheldCall(callId: String, placed: PlacedCall, reason: EndReason) {
        callRepository.endCall(callId, reason)
        callRepository.logCallMessage(placed.chatId, reason, durationSeconds = 0, video = placed.placing.video)
    }

    /** Answer the ringing call. The caller holds the microphone permission, and the camera's when [camera] is true. */
    fun answer(camera: Boolean) = CallService.sendAnswer(context, camera)

    fun decline() = CallService.sendAction(context, CallService.ACTION_DECLINE)

    /** Hang up. A call still being placed is cancelled here, because the service does not hold it yet. */
    fun hangup() {
        if (cancelPlacing()) return
        CallService.sendAction(context, CallService.ACTION_HANGUP)
    }

    fun toggleMute() = CallService.sendAction(context, CallService.ACTION_TOGGLE_MUTE)

    /** Switch the own camera. The caller holds the `CAMERA` permission when [on] is true. */
    fun setCamera(on: Boolean) = CallService.sendSetCamera(context, on)

    fun flipCamera() = CallService.sendAction(context, CallService.ACTION_FLIP_CAMERA)

    /** Move the call's audio to [route]. The OS decides when it actually lands; [uiControls] follows. */
    fun selectAudioRoute(route: CallAudioRoute) = CallService.sendSelectAudioRoute(context, route)

    /** The docked card came on screen, or left it. The camera runs only while a surface shows the call. */
    fun setDockShowing(showing: Boolean) = callStateHolder.setSurfaceShowing(CallSurface.DOCK, showing)

    /** A view that draws [participantId]'s video. Single-use: hand it back with [releaseVideoView]. */
    fun createVideoView(context: Context, participantId: String): View =
        videoSinks.createView(context, participantId)

    fun releaseVideoView(view: View) = videoSinks.releaseView(view)

    /**
     * The call screen is closing for good. Its activity calls this as it finishes: [onCleared]
     * comes only with its onDestroy, which the system can hold back for seconds after Back.
     */
    fun onScreenClosed() {
        closed = true
        cancelPlacing()
    }

    /**
     * Cancel the call this screen is placing. Returns false when its placing is no longer up. A
     * placing whose document exists has rung the callee already, so its call is ended and
     * recorded here.
     */
    private fun cancelPlacing(): Boolean {
        val placed = placed ?: return false
        val cancelled = callStateHolder.cancelPlacing(placed.placing.placingId) ?: return false
        cancelled.callId?.let { callId ->
            appScope.launch { endUnheldCall(callId, placed, EndReason.HANGUP) }
        }
        return true
    }

    override fun onCleared() {
        onScreenClosed()
    }

    internal companion object {
        private const val TAG = "CallViewModel"

        /** How long creating the call document may take before the placing fails. */
        const val CREATE_TIMEOUT_MS = 20_000L

        /** How long the call service may take to take over a created call. */
        const val HANDOVER_TIMEOUT_MS = 10_000L
    }
}
