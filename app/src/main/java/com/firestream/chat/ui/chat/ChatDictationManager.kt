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

        session = scope.launch { runSession(languageTag) }
    }

    fun stop() {
        if (session == null) return
        userStopRequested = true
        recognizer.stop()
    }

    // cancel() discards the in-flight partial; stop() commits it. Both clear
    // listening state, but only stop() emits a Final commit (handled by
    // runSession's finally + the `cancelled` flag).
    fun cancel() {
        if (session == null) return
        userStopRequested = true
        cancelled = true
        session?.cancel()
    }

    fun clearError() {
        _uiState.update { it.copy(dictation = it.dictation.copy(error = null)) }
    }

    private fun joinedWith(segmentText: String): String {
        if (committedSegments.isEmpty()) return segmentText
        val needsSpace = !committedSegments.endsWith(' ')
        return committedSegments.toString() + (if (needsSpace) " " else "") + segmentText
    }

    private suspend fun runSession(languageTag: String) {
        try {
            while (scope.isActive && !userStopRequested) {
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
            committedSegments.clear()
            _audioLevel.value = 0f
            _uiState.update {
                it.copy(dictation = it.dictation.copy(isListening = false))
            }
            session = null
            cancelled = false
        }
    }
}
