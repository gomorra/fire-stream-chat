package com.firestream.chat.data.call

import com.firestream.chat.domain.model.CallAudioRoute
import com.firestream.chat.domain.model.CallParticipant
import com.firestream.chat.domain.model.CallState
import com.firestream.chat.domain.model.CallSurface
import com.firestream.chat.domain.model.CallUiControls
import com.firestream.chat.domain.model.EndReason
import com.firestream.chat.domain.model.isOngoing
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.transformLatest
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.flow.updateAndGet
import java.util.concurrent.atomic.AtomicLong
import javax.inject.Inject
import javax.inject.Singleton

/**
 * What the screens read of the current call, and what a [CallSession] and the call screens write.
 *
 * A call's own state is written on the main thread: by its session, and by the screen that places
 * it. Only the placing is raced for from another thread, and [replaceIf] settles that.
 */
@Singleton
class CallStateHolder @Inject constructor() {

    private val _callState = MutableStateFlow<CallState>(CallState.Idle)
    val callState: StateFlow<CallState> = _callState.asStateFlow()

    private val _uiControls = MutableStateFlow(CallUiControls())
    val uiControls: StateFlow<CallUiControls> = _uiControls.asStateFlow()

    private val _participants = MutableStateFlow<List<CallParticipant>>(emptyList())

    /** The other people in the call: one in a 1:1 call. The own side is in [uiControls]. */
    val participants: StateFlow<List<CallParticipant>> = _participants.asStateFlow()

    /** The call [_chatId] belongs to. */
    private var chatCallId: String? = null
    private val _chatId = MutableStateFlow<String?>(null)

    /**
     * The chat of the current call, where it can dock. Null while it is not known: the side that
     * answers looks it up after the answer. Like the controls, it stays set after the call ended.
     */
    val chatId: StateFlow<String?> = _chatId.asStateFlow()

    private val _surfaces = MutableStateFlow<Set<CallSurface>>(emptySet())

    /** The surfaces that are on screen right now. They outlive a call: a surface reports itself, not a call. */
    val surfaces: StateFlow<Set<CallSurface>> = _surfaces.asStateFlow()

    /**
     * The call is on screen: true while any surface shows it, and false only after
     * [OFF_SCREEN_GRACE_MILLIS] with none. The stage hands over to the docked card and back
     * within that time, so the camera does not blink.
     */
    @OptIn(ExperimentalCoroutinesApi::class)
    val onScreen: Flow<Boolean> = _surfaces
        .map { it.isNotEmpty() }
        .transformLatest { showing ->
            if (!showing) delay(OFF_SCREEN_GRACE_MILLIS)
            emit(showing)
        }
        .distinctUntilChanged()

    /** Numbers each placing, so a setup only ever touches its own. */
    private val placingIds = AtomicLong()

    /**
     * The call [callId] starts with [participants] and with fresh controls, in the chat [chatId]
     * if it is known. A [CallSession] runs this when it takes a call: an outgoing one after its
     * placing, an incoming one when it rings.
     */
    fun beginCall(callId: String, participants: List<CallParticipant>, chatId: String? = null) {
        chatCallId = callId
        _chatId.value = chatId
        _uiControls.value = CallUiControls()
        _participants.value = participants
    }

    /** The chat of the call [callId] is [chatId]. Does nothing when another call has begun since. */
    fun setChatId(callId: String, chatId: String) {
        if (chatCallId == callId) _chatId.value = chatId
    }

    /** [surface] came on screen, or left it. */
    fun setSurfaceShowing(surface: CallSurface, showing: Boolean) {
        _surfaces.update { if (showing) it + surface else it - surface }
    }

    /**
     * Publish [state]. A state that starts a call also resets the controls first. A call starts
     * when an ongoing state follows Idle or Ended.
     *
     * The controls belong to one call. The next call's audio track is created enabled, so a mute
     * left over from the previous call would show "muted" over a live microphone.
     */
    fun updateState(state: CallState) {
        if (state.isOngoing && !_callState.value.isOngoing) _uiControls.value = CallUiControls()
        _callState.value = state
    }

    /**
     * Record that the call [callId] was started as a video call, as one step, whatever live state
     * it is in. Does nothing when another call is current, when the call has ended, or with none.
     *
     * @return the state after the write.
     */
    fun markVideo(callId: String): CallState = _callState.updateAndGet { state ->
        if (state is CallState.Live && state.callId == callId && !state.video) state.withVideo() else state
    }

    /** Change the controls as one step, so a change never writes back a stale copy of another field. */
    fun updateControls(change: (CallUiControls) -> CallUiControls) {
        _uiControls.update(change)
    }

    /**
     * Flip mute and return the new value, so the caller applies to the audio track exactly what the
     * UI shows.
     *
     * @return true when the call is muted after the flip.
     */
    fun toggleMute(): Boolean = _uiControls.updateAndGet { it.copy(isMuted = !it.isMuted) }.isMuted

    /**
     * Publish the routes the OS offers and the one it is actually playing through. A null [current]
     * means the OS has not reported a route yet, and leaves the displayed one alone rather than
     * guessing. Leaves everything else alone either way.
     */
    fun updateAudioRoutes(available: List<CallAudioRoute>, current: CallAudioRoute?) {
        _uiControls.update { it.copy(audioRoute = current ?: it.audioRoute, availableRoutes = available) }
    }

    /** Change the participant [id] as one step. Does nothing when they are not in the call. */
    fun updateParticipant(id: String, change: (CallParticipant) -> CallParticipant) {
        _participants.update { list -> list.map { if (it.id == id) change(it) else it } }
    }

    /** [ids] are the participants whose video has delivered a frame. Everyone else has none. */
    fun setFramed(ids: Set<String>) {
        _participants.update { list -> list.map { it.copy(hasFrame = it.id in ids) } }
    }

    /**
     * Clear the way for an outgoing call, before the microphone prompt. Returns false while another
     * call is ongoing. The caller must not place a second call over it.
     *
     * Otherwise forgets a finished call. The call screen finishes itself 1.5 s after it sees Ended,
     * so a stale Ended would close the new call's screen underneath the prompt. The state stays Idle
     * until [startPlacing], so a call that comes in while the prompt is up still rings.
     */
    fun prepareOutgoingCall(): Boolean = replaceIf { if (it.isOngoing) null else CallState.Idle } != null

    /**
     * Publish a call being placed to [calleeId], with fresh controls and nobody in it yet, and
     * return it. [video] is how the call is started. Returns null while a call is ongoing, a
     * placing included.
     */
    fun startPlacing(
        calleeId: String,
        calleeName: String,
        calleeAvatarUrl: String?,
        video: Boolean = false,
    ): CallState.Placing? {
        val placing = CallState.Placing(placingIds.incrementAndGet(), calleeId, calleeName, calleeAvatarUrl, video = video)
        replaceIf { if (it.isOngoing) null else placing } ?: return null
        _uiControls.value = CallUiControls()
        // The people and the chat of the call before must not show under this one.
        _participants.value = emptyList()
        chatCallId = null
        _chatId.value = null
        return placing
    }

    /**
     * Record that placing [placingId] now has the document [callId]. Returns false when that
     * placing ended meanwhile, or already has a document.
     */
    fun placingCreated(placingId: Long, callId: String): Boolean = replaceIf {
        if (it is CallState.Placing && it.placingId == placingId && it.callId == null) it.copy(callId = callId) else null
    } != null

    /**
     * Let the call service take over the placed call as [ringing]. Returns false unless the call
     * being placed is the one [ringing] names: its placing was cancelled or timed out first, and the
     * service must end that call instead of ringing.
     */
    fun takeOverPlacing(ringing: CallState.OutgoingRinging): Boolean = replaceIf {
        if (it is CallState.Placing && it.callId == ringing.callId) ringing else null
    } != null

    /**
     * End placing [placingId] with an error, so the call screen shows the end and closes. Returns
     * false, and changes nothing, unless that placing is still up. A call that came in meanwhile, or
     * a newer placing, keeps its state.
     */
    fun failPlacing(placingId: Long): Boolean = endPlacing(placingId, EndReason.ERROR) != null

    /**
     * Cancel placing [placingId], at whatever stage, and return it as it was when it ended. Its
     * [CallState.Placing.callId] tells whether its document exists. Returns null unless that placing
     * is still up.
     */
    fun cancelPlacing(placingId: Long): CallState.Placing? = endPlacing(placingId, EndReason.HANGUP)

    private fun endPlacing(placingId: Long, reason: EndReason): CallState.Placing? = replaceIf {
        if (it is CallState.Placing && it.placingId == placingId) CallState.Ended(it.callId ?: "", reason) else null
    } as CallState.Placing?

    /**
     * Replace the state with what [next] makes of it, and return the state it replaced. Changes
     * nothing, and returns null, when [next] returns null. Atomic: the setup on the application
     * scope and the service on the main thread race for the same placing, and exactly one of them
     * wins it.
     */
    private inline fun replaceIf(next: (CallState) -> CallState?): CallState? {
        while (true) {
            val current = _callState.value
            val replacement = next(current) ?: return null
            if (_callState.compareAndSet(current, replacement)) return current
        }
    }

    companion object {
        /** How long the call may be on no surface before the camera pauses. */
        const val OFF_SCREEN_GRACE_MILLIS = 1_000L
    }
}
