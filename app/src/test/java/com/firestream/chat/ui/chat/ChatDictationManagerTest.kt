package com.firestream.chat.ui.chat

import android.content.Context
import com.firestream.chat.data.call.CallStateHolder
import com.firestream.chat.data.util.DictationEvent
import com.firestream.chat.data.util.SpeechRecognizerManager
import com.firestream.chat.domain.model.AppError
import com.firestream.chat.domain.model.CallState
import com.firestream.chat.test.MainDispatcherRule
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.consumeAsFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ChatDictationManagerTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule(StandardTestDispatcher())

    private val recognizer = mockk<SpeechRecognizerManager>(relaxed = true)
    private val callStateHolder = CallStateHolder()
    private val context = mockk<Context>(relaxed = true)
    private val uiState = MutableStateFlow(ChatUiState())

    private val segments = mutableListOf<Channel<DictationEvent>>()

    private fun setUpRecognizer(available: Boolean = true) {
        every { recognizer.isAvailable } returns available
        every { recognizer.isOnDeviceAvailable } returns available
        every { recognizer.listen(any()) } answers {
            val ch = Channel<DictationEvent>(Channel.UNLIMITED)
            segments += ch
            ch.consumeAsFlow()
        }
    }

    private fun TestScope.newManager(): ChatDictationManager {
        setUpRecognizer()
        return ChatDictationManager(
            recognizer = recognizer,
            callStateHolder = callStateHolder,
            context = context,
            _uiState = uiState,
            scope = this,
        ).also { it.init() }
    }

    @Test
    fun `init populates availability flags`() = runTest {
        val manager = newManager()
        assertTrue(uiState.value.dictation.isAvailable)
        // Manager itself unused beyond init in this test.
        manager.cancel()
    }

    @Test
    fun `start does nothing when recognition unavailable`() = runTest {
        every { recognizer.isAvailable } returns false
        every { recognizer.isOnDeviceAvailable } returns false
        val manager = ChatDictationManager(recognizer, callStateHolder, context, uiState, this).also { it.init() }

        manager.start("en-US")
        runCurrent()

        assertFalse(uiState.value.dictation.isListening)
        assertEquals(0, segments.size)
    }

    @Test
    fun `start refuses when call is active`() = runTest {
        val manager = newManager()
        callStateHolder.updateState(
            CallState.Connecting(
                callId = "c1",
                remoteUserId = "u2",
                remoteName = "Alice",
                remoteAvatarUrl = null,
            )
        )

        manager.start("en-US")
        runCurrent()

        assertFalse(uiState.value.dictation.isListening)
        assertNotNull(uiState.value.dictation.error)
        assertEquals(0, segments.size)
    }

    @Test
    fun `partial event emits cumulative DictationCommit`() = runTest {
        val manager = newManager()
        val collected = mutableListOf<DictationCommit>()
        val collector = launch { manager.commits.collect { collected += it } }

        manager.start("en-US")
        runCurrent()
        assertTrue(uiState.value.dictation.isListening)

        segments[0].send(DictationEvent.Partial("hello"))
        runCurrent()

        assertEquals(listOf(DictationCommit.Partial("hello")), collected)
        collector.cancel()
        manager.cancel()
    }

    @Test
    fun `final segment + restart joins with space`() = runTest {
        val manager = newManager()
        val collected = mutableListOf<DictationCommit>()
        val collector = launch { manager.commits.collect { collected += it } }

        manager.start("en-US")
        runCurrent()

        // Segment 1: speak "hello"
        segments[0].send(DictationEvent.Partial("hello"))
        segments[0].send(DictationEvent.Final("hello"))
        segments[0].close()
        // Wait the 120ms restart delay
        advanceTimeBy(150)
        runCurrent()

        // Segment 2: speak "world" — cumulative should be "hello world"
        assertEquals(2, segments.size)
        segments[1].send(DictationEvent.Partial("world"))
        runCurrent()

        // Last emit should be cumulative with space.
        assertEquals(DictationCommit.Partial("hello world"), collected.last())
        collector.cancel()
        manager.cancel()
    }

    @Test
    fun `silent end triggers restart without changing committed text`() = runTest {
        val manager = newManager()
        val collected = mutableListOf<DictationCommit>()
        val collector = launch { manager.commits.collect { collected += it } }

        manager.start("en-US")
        runCurrent()

        // Segment 1: only silence
        segments[0].send(DictationEvent.SilentEnd)
        segments[0].close()
        advanceTimeBy(150)
        runCurrent()

        // Should restart cleanly with empty prefix
        assertEquals(2, segments.size)
        segments[1].send(DictationEvent.Partial("first words"))
        runCurrent()

        assertEquals(DictationCommit.Partial("first words"), collected.last())
        collector.cancel()
        manager.cancel()
    }

    @Test
    fun `stop emits final commit and clears listening state`() = runTest {
        val manager = newManager()
        val collected = mutableListOf<DictationCommit>()
        val collector = launch { manager.commits.collect { collected += it } }

        manager.start("en-US")
        runCurrent()

        segments[0].send(DictationEvent.Partial("hello"))
        runCurrent()

        manager.stop()
        // Real recognizer would fire onResults from stopListening; simulate:
        segments[0].send(DictationEvent.Final("hello"))
        segments[0].close()
        advanceUntilIdle()

        assertEquals(DictationCommit.Final("hello"), collected.last())
        assertFalse(uiState.value.dictation.isListening)
        verify { recognizer.stop() }
        collector.cancel()
    }

    @Test
    fun `cancel does not emit final and clears state`() = runTest {
        val manager = newManager()
        val collected = mutableListOf<DictationCommit>()
        val collector = launch { manager.commits.collect { collected += it } }

        manager.start("en-US")
        runCurrent()
        segments[0].send(DictationEvent.Partial("hello"))
        runCurrent()

        manager.cancel()
        advanceUntilIdle()

        // No Final emitted — only the prior Partial.
        assertTrue(collected.none { it is DictationCommit.Final })
        assertFalse(uiState.value.dictation.isListening)
        collector.cancel()
    }

    @Test
    fun `error event sets state error and stops`() = runTest {
        val manager = newManager()
        val collector = launch { manager.commits.collect { /* drain */ } }

        manager.start("en-US")
        runCurrent()

        segments[0].send(DictationEvent.Error(AppError.Permission("dictate audio")))
        segments[0].close()
        advanceUntilIdle()

        assertNotNull(uiState.value.dictation.error)
        assertFalse(uiState.value.dictation.isListening)
        collector.cancel()
    }

    @Test
    fun `rms event updates audio level on dedicated StateFlow`() = runTest {
        val manager = newManager()
        val collector = launch { manager.commits.collect { /* drain */ } }

        manager.start("en-US")
        runCurrent()

        segments[0].send(DictationEvent.Rms(7.5f))
        runCurrent()

        assertEquals(7.5f, manager.audioLevel.value, 0.001f)
        manager.cancel()
        collector.cancel()
    }

    @Test
    fun `start is idempotent when already listening`() = runTest {
        val manager = newManager()

        manager.start("en-US")
        runCurrent()
        assertEquals(1, segments.size)

        manager.start("en-US")
        runCurrent()
        // Still only one active session.
        assertEquals(1, segments.size)

        manager.cancel()
    }

    @Test
    fun `clearError nulls out the error`() = runTest {
        val manager = newManager()
        val collector = launch { manager.commits.collect { /* drain */ } }

        manager.start("en-US")
        runCurrent()
        segments[0].send(DictationEvent.Error(AppError.Network))
        segments[0].close()
        advanceUntilIdle()
        assertNotNull(uiState.value.dictation.error)

        manager.clearError()

        assertNull(uiState.value.dictation.error)
        collector.cancel()
    }
}
