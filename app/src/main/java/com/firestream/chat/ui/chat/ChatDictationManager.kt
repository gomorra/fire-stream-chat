// region: AGENT-NOTE
// Responsibility: Drives composer voice dictation via the system SpeechRecognizer.
// Owns: ChatUiState.dictation.* (entire slice). Side-channel: `commits` SharedFlow
//   for committed text segments; `audioLevel` StateFlow kept separate so RMS
//   updates (~10/s) don't recompose all of ChatScreen.
// Collaborators: ChatViewModel (composition root), SpeechRecognizerManager,
//   CallStateHolder (suppresses dictation during a call).
// Don't put here: language-pref persistence (DataStore — owned by SettingsViewModel),
//   composer text writes (commits flow → ChatViewModel applies them). Pattern:
//   docs/PATTERNS.md#chat-manager-slice-ownership — this file is the canonical
//   clean example.
// endregion

package com.firestream.chat.ui.chat

import android.content.Context
import android.util.Log
import com.firestream.chat.R
import com.firestream.chat.data.call.CallStateHolder
import com.firestream.chat.data.util.DictationEvent
import com.firestream.chat.data.util.SpeechRecognizerManager
import com.firestream.chat.domain.model.AppError
import com.firestream.chat.domain.model.CallState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.coroutines.coroutineContext

internal class ChatDictationManager(
    private val recognizer: SpeechRecognizerManager,
    private val callStateHolder: CallStateHolder,
    private val context: Context,
    private val _uiState: MutableStateFlow<ChatUiState>,
    private val scope: CoroutineScope,
) {

    private val _commits = MutableSharedFlow<DictationCommit>(extraBufferCapacity = 16)
    val commits: SharedFlow<DictationCommit> = _commits.asSharedFlow()

    // Separate StateFlow keeps RMS updates (~10/sec) from recomposing all of
    // ChatScreen — only DictationControlBar collects this.
    private val _audioLevel = MutableStateFlow(0f)
    val audioLevel: StateFlow<Float> = _audioLevel.asStateFlow()

    private var session: Job? = null
    private val committedSegments = StringBuilder()
    @Volatile private var userStopRequested = false
    @Volatile private var cancelled = false

    fun init() {
        _uiState.update {
            it.copy(
                dictation = it.dictation.copy(
                    isAvailable = recognizer.isAvailable,
                    isOnDeviceAvailable = recognizer.isOnDeviceAvailable,
                )
            )
        }
    }

    fun start(languageTag: String) {
        if (session != null) return
        if (!_uiState.value.dictation.isAvailable) return
        if (callStateHolder.callState.value !is CallState.Idle) {
            _uiState.update {
                it.copy(dictation = it.dictation.copy(
                    error = AppError.Validation(context.getString(R.string.dictation_in_call))
                ))
            }
            return
        }

        committedSegments.clear()
        userStopRequested = false
        cancelled = false
        _audioLevel.value = 0f
        _uiState.update {
            it.copy(dictation = it.dictation.copy(isListening = true, error = null))
        }

        Log.d(TAG, "start(languageTag=$languageTag)")
        session = scope.launch { runSession(languageTag) }
    }

    fun stop() {
        val active = session
        if (active == null) {
            // Stale-flag repair: isListening can survive a session that never
            // ran (scope already cancelled) or a desync with a leaked
            // recognizer. Without this reset the composer stays owned by
            // dictation and the cancel-on-type escape hatch is a no-op.
            Log.w(TAG, "stop() with no session — resetting stale dictation state")
            resetState()
            return
        }
        userStopRequested = true
        recognizer.stop()
        // stopListening() doesn't always produce onResults (OEM flakiness, or
        // a clobbered recognizer registration) — force-cancel if this session
        // is still alive after the grace period. Committed segments were
        // already emitted as Partial commits, so only an unrefined final can
        // be lost.
        scope.launch {
            delay(STOP_WATCHDOG_MS)
            if (userStopRequested && session === active) {
                Log.w(TAG, "stop() watchdog fired — force-cancelling hung session")
                active.cancel()
            }
        }
    }

    // cancel() discards the in-flight partial; stop() commits it. Both clear
    // listening state, but only stop() emits a Final commit (handled by
    // runSession's finally + the `cancelled` flag).
    fun cancel() {
        userStopRequested = true
        cancelled = true
        val active = session
        session = null
        active?.cancel()
        if (active == null) {
            Log.w(TAG, "cancel() with no session — resetting stale dictation state")
        }
        resetState()
    }

    fun clearError() {
        _uiState.update { it.copy(dictation = it.dictation.copy(error = null)) }
    }

    private fun joinedWith(segmentText: String): String {
        if (committedSegments.isEmpty()) return segmentText
        val needsSpace = !committedSegments.endsWith(' ')
        return committedSegments.toString() + (if (needsSpace) " " else "") + segmentText
    }

    private fun resetState() {
        committedSegments.clear()
        _audioLevel.value = 0f
        _uiState.update {
            it.copy(dictation = it.dictation.copy(isListening = false))
        }
    }

    private suspend fun runSession(languageTag: String) {
        try {
            var consecutiveSilentSegments = 0
            while (scope.isActive && !userStopRequested) {
                val committedBefore = committedSegments.length
                recognizer.listen(languageTag).collect { event ->
                    when (event) {
                        is DictationEvent.Partial -> {
                            _commits.tryEmit(DictationCommit.Partial(joinedWith(event.text)))
                        }

                        is DictationEvent.Final -> {
                            if (event.text.isNotEmpty()) {
                                if (committedSegments.isNotEmpty() && !committedSegments.endsWith(' ')) {
                                    committedSegments.append(' ')
                                }
                                committedSegments.append(event.text)
                                _commits.tryEmit(DictationCommit.Partial(committedSegments.toString()))
                            }
                        }

                        is DictationEvent.SilentEnd -> {
                            // Recognizer stopped without text — restart loop will re-engage.
                        }

                        is DictationEvent.Error -> {
                            _uiState.update {
                                it.copy(dictation = it.dictation.copy(error = event.error))
                            }
                            userStopRequested = true
                        }

                        is DictationEvent.Rms -> {
                            _audioLevel.value = event.db
                        }
                    }
                }
                if (userStopRequested) break
                // Cap the restart churn: each restart creates a fresh system
                // recognizer, and an unbounded loop can batter the external
                // RecognitionService into a wedged state that only a reboot
                // clears. Consecutive segments with no committed text mean
                // nobody is talking — stop gracefully.
                if (committedSegments.length > committedBefore) {
                    consecutiveSilentSegments = 0
                } else {
                    consecutiveSilentSegments++
                    if (consecutiveSilentSegments >= MAX_SILENT_RESTARTS) {
                        Log.w(TAG, "$consecutiveSilentSegments consecutive silent segments — ending dictation")
                        break
                    }
                }
                // Small gap before restart so the recognizer fully releases its mic resources.
                delay(120)
            }
        } finally {
            // cancel() suppresses the Final commit; stop() (and natural end) emit it.
            if (!cancelled) {
                val finalText = committedSegments.toString()
                if (finalText.isNotEmpty()) {
                    _commits.tryEmit(DictationCommit.Final(finalText))
                }
            }
            Log.d(TAG, "session ended (cancelled=$cancelled, committed=${committedSegments.length} chars)")
            // A newer session may already own the shared state (cancel() nulls
            // `session` before this finally runs) — only clean up if this
            // coroutine is still the current session.
            if (session === coroutineContext[Job]) {
                session = null
                resetState()
                cancelled = false
            }
        }
    }

    private companion object {
        const val TAG = "ChatDictation"
        const val STOP_WATCHDOG_MS = 2_000L
        const val MAX_SILENT_RESTARTS = 5
    }
}
