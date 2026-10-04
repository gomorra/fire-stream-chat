package com.firestream.chat.data.call

import com.firestream.chat.domain.model.CallAudioRoute
import com.firestream.chat.domain.model.CallParticipant
import com.firestream.chat.domain.model.CallState
import com.firestream.chat.domain.model.CallUiControls
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
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

    /**
     * A call starts with [participants] and with fresh controls. The controls of the call before
     * are still set until this runs: nothing clears them when a call ends, so the ended screen can
     * go on showing them.
     */
    fun beginCall(participants: List<CallParticipant>) {
        _uiControls.value = CallUiControls()
        _participants.value = participants
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
    }
}
