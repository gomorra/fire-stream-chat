package com.firestream.chat.data.call

import com.firestream.chat.domain.model.CallAudioRoute
import com.firestream.chat.domain.model.CallParticipant
import com.firestream.chat.domain.model.CallState
import com.firestream.chat.domain.model.CallSurface
import com.firestream.chat.domain.model.CallUiControls
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
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class CallStateHolder @Inject constructor() {

    private val _callState = MutableStateFlow<CallState>(CallState.Idle)
    val callState: StateFlow<CallState> = _callState.asStateFlow()

    private val _uiControls = MutableStateFlow(CallUiControls())
    val uiControls: StateFlow<CallUiControls> = _uiControls.asStateFlow()

    private val _participants = MutableStateFlow<List<CallParticipant>>(emptyList())

    /** The other people in the call: one in a 1:1 call. The own side is in [uiControls]. */
    val participants: StateFlow<List<CallParticipant>> = _participants.asStateFlow()

    private val chatLock = Any()

    /** The call [_chatId] belongs to. Guarded by [chatLock]. */
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

    /**
     * The call [callId] starts with [participants] and with fresh controls, in the chat [chatId]
     * if the caller knows it. The controls of the call before are still set until this runs:
     * nothing clears them when a call ends, so the ended screen can go on showing them.
     */
    fun beginCall(callId: String, participants: List<CallParticipant>, chatId: String? = null) {
        synchronized(chatLock) {
            chatCallId = callId
            _chatId.value = chatId
        }
        _uiControls.value = CallUiControls()
        _participants.value = participants
    }

    /** The chat of the call [callId] is [chatId]. Does nothing when another call has begun since. */
    fun setChatId(callId: String, chatId: String) {
        synchronized(chatLock) {
            if (chatCallId == callId) _chatId.value = chatId
        }
    }

    /** [surface] came on screen, or left it. */
    fun setSurfaceShowing(surface: CallSurface, showing: Boolean) {
        _surfaces.update { if (showing) it + surface else it - surface }
    }

    fun updateState(state: CallState) {
        _callState.value = state
    }

    /**
     * Move to [next] only if the state is still [expected], as one step. For a writer that must
     * not overwrite a state another thread set in the meantime.
     *
     * @return true when the state was changed.
     */
    fun compareAndSetState(expected: CallState, next: CallState): Boolean =
        _callState.compareAndSet(expected, next)

    /**
     * Record that the call [callId] was started as a video call, as one step, whatever live state
     * it is in. Does nothing when another call is current, when the call has ended, or with none.
     *
     * @return the state after the write.
     */
    fun markVideo(callId: String): CallState = _callState.updateAndGet { state ->
        if (state is CallState.Live && state.callId == callId && !state.video) state.withVideo() else state
    }

    fun updateControls(controls: CallUiControls) {
        _uiControls.value = controls
    }

    /** Change the controls as one step. Writers on different threads do not lose each other's change. */
    fun updateControls(change: (CallUiControls) -> CallUiControls) {
        _uiControls.update(change)
    }

    /** @return true when the call is muted after the flip. */
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

    fun reset() {
        _callState.value = CallState.Idle
        _uiControls.value = CallUiControls()
        _participants.value = emptyList()
        synchronized(chatLock) {
            chatCallId = null
            _chatId.value = null
        }
    }

    companion object {
        /** How long the call may be on no surface before the camera pauses. */
        const val OFF_SCREEN_GRACE_MILLIS = 1_000L
    }
}
