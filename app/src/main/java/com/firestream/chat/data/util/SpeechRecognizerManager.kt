package com.firestream.chat.data.util

import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.util.Log
import com.firestream.chat.R
import com.firestream.chat.domain.model.AppError
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

internal sealed interface DictationEvent {
    data class Partial(val text: String) : DictationEvent
    data class Final(val text: String) : DictationEvent
    data object SilentEnd : DictationEvent
    data class Error(val error: AppError) : DictationEvent
    data class Rms(val db: Float) : DictationEvent
}

@Singleton
class SpeechRecognizerManager @Inject constructor(
    @ApplicationContext private val context: Context
) {
    private val mainHandler = Handler(Looper.getMainLooper())

    @Volatile
    private var currentRecognizer: SpeechRecognizer? = null

    // Cached @Singleton: PackageManager.queryIntentServices is a Binder call and
    // the result is stable for the process lifetime.
    val isAvailable: Boolean by lazy { SpeechRecognizer.isRecognitionAvailable(context) }

    val isOnDeviceAvailable: Boolean by lazy {
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
            SpeechRecognizer.isOnDeviceRecognitionAvailable(context)
    }

    internal fun listen(languageTag: String): Flow<DictationEvent> = callbackFlow {
        if (!isAvailable) {
            trySend(DictationEvent.Error(AppError.Validation(context.getString(R.string.dictation_unavailable))))
            close()
            return@callbackFlow
        }

        val listener = object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) {}
            override fun onBeginningOfSpeech() {}
            override fun onBufferReceived(buffer: ByteArray?) {}
            override fun onEndOfSpeech() {}
            override fun onEvent(eventType: Int, params: Bundle?) {}

            override fun onRmsChanged(rmsdB: Float) {
                trySend(DictationEvent.Rms(rmsdB))
            }

            override fun onPartialResults(partialResults: Bundle?) {
                val text = partialResults?.firstResult().orEmpty()
                if (text.isNotEmpty()) trySend(DictationEvent.Partial(text))
            }

            override fun onResults(results: Bundle?) {
                val text = results?.firstResult().orEmpty()
                trySend(DictationEvent.Final(text))
                close()
            }

            override fun onError(error: Int) {
                Log.w(TAG, "SpeechRecognizer onError code=$error (${errorName(error)})")
                when (error) {
                    SpeechRecognizer.ERROR_NO_MATCH,
                    SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> trySend(DictationEvent.SilentEnd)

                    SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS ->
                        trySend(DictationEvent.Error(AppError.Permission("dictate audio")))

                    SpeechRecognizer.ERROR_NETWORK,
                    SpeechRecognizer.ERROR_NETWORK_TIMEOUT,
                    SpeechRecognizer.ERROR_SERVER,
                    SpeechRecognizer.ERROR_SERVER_DISCONNECTED ->
                        trySend(DictationEvent.Error(AppError.Network))

                    else -> trySend(
                        DictationEvent.Error(
                            AppError.Unknown(RuntimeException("Speech recognition error ${errorName(error)} ($error)"))
                        )
                    )
                }
                close()
            }
        }

        // SpeechRecognizer must be created and driven from the main thread.
        val recognizer = withContext(Dispatchers.Main.immediate) {
            SpeechRecognizer.createSpeechRecognizer(context).also { rec ->
                rec.setRecognitionListener(listener)
                rec.startListening(buildIntent(languageTag))
            }
        }
        currentRecognizer = recognizer

        awaitClose {
            currentRecognizer = null
            mainHandler.post {
                runCatching { recognizer.cancel() }
                runCatching { recognizer.destroy() }
            }
        }
    }

    fun stop() {
        val rec = currentRecognizer ?: return
        mainHandler.post { runCatching { rec.stopListening() } }
    }

    private fun buildIntent(languageTag: String): Intent =
        Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, languageTag)
            putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, context.packageName)
            // Hints to suppress silence-based auto-stop; honored inconsistently across OEMs,
            // so ChatDictationManager also restarts the recognizer between segments.
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, SILENCE_HINT_MS)
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, SILENCE_HINT_MS)
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_MINIMUM_LENGTH_MILLIS, SILENCE_HINT_MS)
            // EXTRA_PREFER_OFFLINE is intentionally not set: forcing offline-only fails
            // immediately with ERROR_NETWORK on devices that don't have an offline language
            // pack downloaded for the requested locale (Samsung Bixby is especially picky).
            // Letting the system pick falls back to online when offline isn't ready.
        }

    private fun Bundle.firstResult(): String? =
        getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()

    private fun errorName(code: Int): String = when (code) {
        SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> "ERROR_NETWORK_TIMEOUT"
        SpeechRecognizer.ERROR_NETWORK -> "ERROR_NETWORK"
        SpeechRecognizer.ERROR_AUDIO -> "ERROR_AUDIO"
        SpeechRecognizer.ERROR_SERVER -> "ERROR_SERVER"
        SpeechRecognizer.ERROR_CLIENT -> "ERROR_CLIENT"
        SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "ERROR_SPEECH_TIMEOUT"
        SpeechRecognizer.ERROR_NO_MATCH -> "ERROR_NO_MATCH"
        SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> "ERROR_RECOGNIZER_BUSY"
        SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "ERROR_INSUFFICIENT_PERMISSIONS"
        SpeechRecognizer.ERROR_TOO_MANY_REQUESTS -> "ERROR_TOO_MANY_REQUESTS"
        SpeechRecognizer.ERROR_SERVER_DISCONNECTED -> "ERROR_SERVER_DISCONNECTED"
        SpeechRecognizer.ERROR_LANGUAGE_NOT_SUPPORTED -> "ERROR_LANGUAGE_NOT_SUPPORTED"
        SpeechRecognizer.ERROR_LANGUAGE_UNAVAILABLE -> "ERROR_LANGUAGE_UNAVAILABLE"
        else -> "UNKNOWN"
    }

    private companion object {
        const val SILENCE_HINT_MS = 60_000L
        const val TAG = "SpeechRecognizer"
    }
}
