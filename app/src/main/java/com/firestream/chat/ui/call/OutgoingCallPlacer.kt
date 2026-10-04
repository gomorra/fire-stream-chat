package com.firestream.chat.ui.call

import android.content.Context
import android.util.Log
import com.firestream.chat.data.call.CallService
import com.firestream.chat.di.ApplicationScope
import com.firestream.chat.domain.model.EndReason
import com.firestream.chat.domain.model.OutgoingCall
import com.firestream.chat.domain.repository.CallRepository
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

/**
 * A call the user asked for that has no call document yet.
 *
 * @param video how the call is started: with the camera on, or as a voice call.
 * @param failed the call could not be created. The stage says so and closes.
 */
data class PlacingCall(
    val calleeId: String,
    val calleeName: String,
    val calleeAvatarUrl: String?,
    val chatId: String,
    val video: Boolean,
    val failed: Boolean = false
)

/**
 * Places an outgoing call. `CallRepository.createCall` can take a few seconds, because it reads
 * what the callee's app can do before the call document exists. That wait runs on the application
 * scope, so it outlives the activity that asked for the call, and [placing] lets the stage show the
 * call from the first frame.
 *
 * A [cancel] during the wait does not stop the write. A call document that is created after it is
 * ended at once, so the callee's phone stops ringing.
 *
 * @param startCall hands the created call to the call service.
 */
@Singleton
class OutgoingCallPlacer internal constructor(
    private val callRepository: CallRepository,
    private val scope: CoroutineScope,
    private val startCall: (PlacingCall, OutgoingCall) -> Unit
) {

    @Inject constructor(
        callRepository: CallRepository,
        @ApplicationScope scope: CoroutineScope,
        @ApplicationContext context: Context
    ) : this(
        callRepository = callRepository,
        scope = scope,
        startCall = { request, call ->
            CallService.startOutgoing(
                context, call.callId, request.chatId, request.calleeId, request.calleeName,
                request.calleeAvatarUrl, request.video,
                // From createCall and never from a guess: an app without video crashes on an offer
                // with a video line.
                videoLine = call.videoLine
            )
        }
    )

    private val _placing = MutableStateFlow<PlacingCall?>(null)

    /** The call that is being placed, or the one that just failed. Null once the service has it. */
    val placing: StateFlow<PlacingCall?> = _placing.asStateFlow()

    /**
     * Place [request].
     *
     * @return false when another call is still being placed. Nothing happens then.
     */
    fun place(request: PlacingCall): Boolean {
        val current = _placing.value
        if (current != null && !current.failed) return false
        if (!_placing.compareAndSet(current, request)) return false
        scope.launch {
            callRepository.createCall(request.calleeId, request.video)
                .onSuccess { call -> onCreated(request, call) }
                .onFailure { error ->
                    Log.w(TAG, "The call could not be created", error)
                    _placing.compareAndSet(request, request.copy(failed = true))
                }
        }
        return true
    }

    /** The user hung up during the wait, or the stage has shown the failure. */
    fun cancel() {
        _placing.value = null
    }

    private suspend fun onCreated(request: PlacingCall, call: OutgoingCall) {
        // Taken out first: from here the call belongs to the service, or to nobody.
        if (!_placing.compareAndSet(request, null)) {
            callRepository.endCall(call.callId, EndReason.HANGUP.name.lowercase())
            return
        }
        try {
            startCall(request, call)
        } catch (e: RuntimeException) {
            // The app left the foreground during the wait, and the system refuses the service.
            Log.w(TAG, "The call service could not be started", e)
            callRepository.endCall(call.callId, EndReason.ERROR.name.lowercase())
            _placing.compareAndSet(null, request.copy(failed = true))
        }
    }

    private companion object {
        const val TAG = "OutgoingCallPlacer"
    }
}
