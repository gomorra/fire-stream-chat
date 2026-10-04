// region: AGENT-NOTE
// Responsibility: Publish this side's live camera and microphone state on the document of a
//   1:1 call (`media.<uid>`), and decide whether the call may be written to at all.
// Owns: The state to publish, the one job that writes it, and the rule that nothing is written
//   before the call is connected and both sides agreed on the video line.
// Collaborators: CallService (tells it what happens to the call and what the user switched),
//   CallRepository.setMedia (the write).
// Don't put here: Reading the other side's state (CallService.observeCallDocument), the camera
//   itself (LocalCamera), a group call's member rows (a group call does not use this document).
// endregion

package com.firestream.chat.data.call

import android.util.Log
import com.firestream.chat.domain.model.CallMedia
import com.firestream.chat.domain.repository.CallRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * Writes what this side says about its camera and microphone to the call document: once when
 * writing starts, and again on every change.
 *
 * Writing starts when the call is connected **and** both sides agreed on the video line, whichever
 * comes last. An app without video never agrees, so it never sees one of these writes. It must
 * not: a released app that placed the call applies the answer again on every change of an answered
 * call document, and ends the call when that fails.
 *
 * Every function may come from any thread. [onConnected] and [onVideoLine] name their call, so an
 * event that was still on its way when its call ended does nothing to the next one.
 *
 * @param scope the owner's scope. The writes run in it.
 */
class CallMediaPublisher(
    private val callRepository: CallRepository,
    private val scope: CoroutineScope
) {

    private val media = MutableStateFlow(CallMedia())

    private val lock = Any()

    // Guarded by [lock].
    private var callId: String? = null
    private var connected = false
    private var videoLineAgreed = false
    private var writer: Job? = null

    /** A call starts: camera off, microphone on, and nothing written yet. Ends the call before it. */
    fun begin(callId: String) {
        synchronized(lock) {
            end()
            media.value = CallMedia()
            this.callId = callId
        }
    }

    /** The user switched something. Written at once while writing, and with the first write otherwise. */
    fun update(change: (CallMedia) -> CallMedia) {
        media.update(change)
    }

    /** The connection of [callId] is up. Reported again when a dropped connection comes back. */
    fun onConnected(callId: String) {
        synchronized(lock) {
            if (this.callId != callId) return
            connected = true
            startIfReady(callId)
        }
    }

    /** Offer and answer of [callId] are applied. [agreed] is true when both sides take video. */
    fun onVideoLine(callId: String, agreed: Boolean) {
        synchronized(lock) {
            if (this.callId != callId) return
            videoLineAgreed = agreed
            startIfReady(callId)
        }
    }

    /** The call is over: nothing more is written. A second call does nothing. */
    fun end() {
        synchronized(lock) {
            writer?.cancel()
            writer = null
            callId = null
            connected = false
            videoLineAgreed = false
        }
    }

    /** Caller holds [lock]. */
    private fun startIfReady(callId: String) {
        if (!connected || !videoLineAgreed || writer != null) return
        writer = scope.launch {
            // collectLatest: a write that waits for the network does not hold back the next one.
            // Each write is made before the next begins, and Firestore sends them in that order.
            media.collectLatest { state ->
                callRepository.setMedia(callId, state.camera, state.mic)
                    .onFailure { e -> Log.w(TAG, "Could not publish the camera and microphone state", e) }
            }
        }
    }

    private companion object {
        const val TAG = "CallMediaPublisher"
    }
}
