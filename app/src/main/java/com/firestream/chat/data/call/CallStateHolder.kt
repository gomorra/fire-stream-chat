package com.firestream.chat.data.call

import com.firestream.chat.domain.model.CallAudioRoute
import com.firestream.chat.domain.model.CallState
import com.firestream.chat.domain.model.CallUiControls
import com.firestream.chat.domain.model.EndReason
import com.firestream.chat.domain.model.isOngoing
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.flow.updateAndGet
import java.util.concurrent.atomic.AtomicLong
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class CallStateHolder @Inject constructor() {

    private val _callState = MutableStateFlow<CallState>(CallState.Idle)
    val callState: StateFlow<CallState> = _callState.asStateFlow()

    private val _uiControls = MutableStateFlow(CallUiControls())
    val uiControls: StateFlow<CallUiControls> = _uiControls.asStateFlow()

    /** Numbers each placing, so a setup only ever touches its own. */
    private val placingIds = AtomicLong()

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

    fun updateControls(controls: CallUiControls) {
        _uiControls.value = controls
    }

    /**
     * Flip mute and return the new value, so the caller applies to the audio track exactly what the
     * UI shows. Atomic, like [updateAudioRoutes], so neither writes back a stale copy of the other's
     * field.
     */
    fun toggleMute(): Boolean = _uiControls.updateAndGet { it.copy(isMuted = !it.isMuted) }.isMuted

    /**
     * Publish the routes the OS offers and the one it is actually playing through. A null [current]
     * means the OS has not reported a route yet, and leaves the displayed one alone rather than
     * guessing. Leaves mute alone either way.
     */
    fun updateAudioRoutes(available: List<CallAudioRoute>, current: CallAudioRoute?) {
        _uiControls.update {
            it.copy(audioRoute = current ?: it.audioRoute, availableRoutes = available)
        }
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
     * Publish a call being placed to [calleeId], with fresh controls, and return it. Returns null
     * while a call is ongoing, a placing included.
     */
    fun startPlacing(calleeId: String, calleeName: String, calleeAvatarUrl: String?): CallState.Placing? {
        val placing = CallState.Placing(placingIds.incrementAndGet(), calleeId, calleeName, calleeAvatarUrl)
        replaceIf { if (it.isOngoing) null else placing } ?: return null
        _uiControls.value = CallUiControls()
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

}
