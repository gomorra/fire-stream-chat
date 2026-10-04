package com.firestream.chat.ui.call

import android.content.Context
import android.view.View
import androidx.lifecycle.ViewModel
import com.firestream.chat.data.call.CallService
import com.firestream.chat.data.call.CallStateHolder
import com.firestream.chat.data.call.CallVideoSinks
import com.firestream.chat.domain.model.CallAudioRoute
import com.firestream.chat.domain.model.CallParticipant
import com.firestream.chat.domain.model.CallState
import com.firestream.chat.domain.model.CallSurface
import com.firestream.chat.domain.model.CallUiControls
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.StateFlow
import javax.inject.Inject

/** What both surfaces of a call read and send: the stage in `CallActivity`, and the docked card in a chat. */
@HiltViewModel
class CallViewModel @Inject constructor(
    private val callStateHolder: CallStateHolder,
    private val callPlacer: OutgoingCallPlacer,
    private val videoSinks: CallVideoSinks,
    @ApplicationContext private val context: Context
) : ViewModel() {

    val callState: StateFlow<CallState> = callStateHolder.callState
    val uiControls: StateFlow<CallUiControls> = callStateHolder.uiControls
    val participants: StateFlow<List<CallParticipant>> = callStateHolder.participants

    /** The chat the call can dock over, or null while it is not known. */
    val callChatId: StateFlow<String?> = callStateHolder.chatId

    /** The surfaces that show the call right now. */
    val surfaces: StateFlow<Set<CallSurface>> = callStateHolder.surfaces

    /** An outgoing call that has no call document yet, see [OutgoingCallPlacer]. */
    val placing: StateFlow<PlacingCall?> = callPlacer.placing

    fun decline() = CallService.sendAction(context, CallService.ACTION_DECLINE)

    /**
     * Hang up. A call that is still being placed is dropped instead, because the service does not
     * have it yet.
     *
     * @return true when nothing is left for the stage to show.
     */
    fun hangup(): Boolean {
        if (callPlacer.placing.value != null) {
            callPlacer.cancel()
            return true
        }
        CallService.sendAction(context, CallService.ACTION_HANGUP)
        return false
    }

    fun cancelPlacing() = callPlacer.cancel()

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
}
