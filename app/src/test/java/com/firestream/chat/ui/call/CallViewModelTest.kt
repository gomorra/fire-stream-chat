package com.firestream.chat.ui.call

import android.app.Application
import android.content.ComponentName
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import androidx.test.core.app.ApplicationProvider
import com.firestream.chat.data.call.CallService
import com.firestream.chat.data.call.CallStateHolder
import com.firestream.chat.domain.model.CallState
import com.firestream.chat.domain.model.EndReason
import com.firestream.chat.domain.model.OutgoingCall
import com.firestream.chat.domain.repository.CallRepository
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * Setting up an outgoing call: creating its document, then handing it to [CallService]. The
 * document rings the callee as soon as it exists, so the setup must not be lost, doubled or left
 * behind by the screen that started it.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [31], application = Application::class)
class CallViewModelTest {

    private val app = ApplicationProvider.getApplicationContext<Application>()
    private val holder = CallStateHolder()
    private val callRepository = mockk<CallRepository>()
    private val created = CompletableDeferred<Result<OutgoingCall>>()

    @Before
    fun setUp() {
        coEvery { callRepository.createCall(CALLEE, any()) } coAnswers { created.await() }
        coEvery { callRepository.endCall(any(), any()) } returns Result.success(Unit)
        coEvery { callRepository.logCallMessage(any(), any(), any(), any()) } returns Result.success(Unit)
    }

    /** The application scopes of the test's ViewModels, cancelled after each test. */
    private val scopes = mutableListOf<CoroutineScope>()

    @After
    fun tearDown() {
        scopes.forEach { it.cancel() }
    }

    /**
     * A ViewModel held by [store], as the call screen's activity holds it. A rotation keeps the
     * store. Clearing it is the screen closing for good.
     *
     * Its application scope is a root scope on the test dispatcher. `backgroundScope` would not
     * do: `advanceUntilIdle()` leaves its work unrun. Nor would the test scope: a create outlives
     * a setup that gave up on it, and the test would wait at its end for a create it never
     * completes.
     */
    private fun TestScope.viewModelIn(store: ViewModelStore, context: Context = app): CallViewModel {
        val appScope = CoroutineScope(coroutineContext + SupervisorJob()).also { scopes += it }
        val factory = viewModelFactory {
            initializer { CallViewModel(holder, callRepository, mockk(relaxed = true), appScope, context) }
        }
        return ViewModelProvider(store, factory)[CallViewModel::class.java]
    }

    /** Place a call as the call screen does: prepare before the microphone prompt, then place. */
    private fun CallViewModel.placeTestCall(callee: String = CALLEE, video: Boolean = false) {
        if (holder.prepareOutgoingCall()) placeCall(callee, "chat1", "Alice", null, video)
    }

    @Test
    fun `a created call is handed to the call service`() = runTest {
        viewModelIn(ViewModelStore()).placeTestCall()
        runCurrent()
        // The call shows as being placed until the service has it.
        assertEquals(CALLEE, (holder.callState.value as CallState.Placing).calleeId)
        assertNull(shadowOf(app).nextStartedService)

        created.complete(Result.success(OutgoingCall("call1", videoLine = false)))
        advanceUntilIdle()

        val started = shadowOf(app).nextStartedService
        assertEquals(CallService.ACTION_START_OUTGOING, started?.action)
        assertEquals("call1", started?.getStringExtra(CallService.EXTRA_CALL_ID))
    }

    @Test
    fun `a video call is created as one and handed over with the video line createCall found`() = runTest {
        viewModelIn(ViewModelStore()).placeTestCall(video = true)
        runCurrent()
        assertEquals(true, (holder.callState.value as CallState.Placing).video)

        created.complete(Result.success(OutgoingCall("call1", videoLine = true)))
        advanceUntilIdle()

        coVerify { callRepository.createCall(CALLEE, true) }
        val started = shadowOf(app).nextStartedService
        assertEquals(true, started?.getBooleanExtra(CallService.EXTRA_VIDEO, false))
        assertEquals(true, started?.getBooleanExtra(CallService.EXTRA_VIDEO_LINE, false))
    }

    // An app without video crashes on an offer with a video line, so how the call was started
    // must never stand in for what the callee's app takes.
    @Test
    fun `a video call to an app without video is handed over without a video line`() = runTest {
        viewModelIn(ViewModelStore()).placeTestCall(video = true)

        created.complete(Result.success(OutgoingCall("call1", videoLine = false)))
        advanceUntilIdle()

        val started = shadowOf(app).nextStartedService
        assertEquals(true, started?.getBooleanExtra(CallService.EXTRA_VIDEO, false))
        assertEquals(false, started?.getBooleanExtra(CallService.EXTRA_VIDEO_LINE, true))
    }

    @Test
    fun `a voice call to an app with video carries the video line`() = runTest {
        viewModelIn(ViewModelStore()).placeTestCall(video = false)

        created.complete(Result.success(OutgoingCall("call1", videoLine = true)))
        advanceUntilIdle()

        coVerify { callRepository.createCall(CALLEE, false) }
        val started = shadowOf(app).nextStartedService
        assertEquals(false, started?.getBooleanExtra(CallService.EXTRA_VIDEO, true))
        assertEquals(true, started?.getBooleanExtra(CallService.EXTRA_VIDEO_LINE, false))
    }

    @Test
    fun `a cancelled video call is recorded as a video call`() = runTest {
        val viewModel = viewModelIn(ViewModelStore())
        viewModel.placeTestCall(video = true)
        created.complete(Result.success(OutgoingCall("call1", videoLine = true)))
        runCurrent()

        viewModel.hangup()
        runCurrent()

        coVerify { callRepository.logCallMessage("chat1", EndReason.HANGUP, 0, true) }
    }

    @Test
    fun `a new call can be placed after one that could not be created`() = runTest {
        val viewModel = viewModelIn(ViewModelStore())
        coEvery { callRepository.createCall(CALLEE, any()) } returns Result.failure(Exception("offline"))
        viewModel.placeTestCall()
        advanceUntilIdle()
        assertNull(shadowOf(app).nextStartedService)

        coEvery { callRepository.createCall(CALLEE, any()) } returns Result.success(OutgoingCall("call2", videoLine = false))
        viewModel.placeTestCall()
        runCurrent()

        assertEquals("call2", shadowOf(app).nextStartedService?.getStringExtra(CallService.EXTRA_CALL_ID))
    }

    @Test
    fun `closing the screen during setup ends the call instead of starting it`() = runTest {
        val store = ViewModelStore()
        viewModelIn(store).placeTestCall()
        runCurrent()

        store.clear()
        created.complete(Result.success(OutgoingCall("call1", videoLine = false)))
        advanceUntilIdle()

        assertNull(shadowOf(app).nextStartedService)
        coVerify { callRepository.endCall("call1", EndReason.HANGUP) }
    }

    @Test
    fun `a screen that is closing ends the call before its ViewModel is cleared`() = runTest {
        // Back finishes the activity at once, but the system can hold back its onDestroy, and
        // with it onCleared, for seconds.
        val viewModel = viewModelIn(ViewModelStore())
        viewModel.placeTestCall()
        runCurrent()

        viewModel.onScreenClosed()
        created.complete(Result.success(OutgoingCall("call1", videoLine = false)))
        advanceUntilIdle()

        assertNull(shadowOf(app).nextStartedService)
        coVerify { callRepository.endCall("call1", EndReason.HANGUP) }
    }

    @Test
    fun `a second request during setup places one call`() = runTest {
        val viewModel = viewModelIn(ViewModelStore())
        viewModel.placeTestCall()
        viewModel.placeTestCall()

        created.complete(Result.success(OutgoingCall("call1", videoLine = false)))
        advanceUntilIdle()

        coVerify(exactly = 1) { callRepository.createCall(CALLEE, any()) }
    }

    @Test
    fun `a second request before the service has taken the call places one call`() = runTest {
        // The service takes the call over on the main thread a moment after it is handed over,
        // and the call is still being placed until then.
        val viewModel = viewModelIn(ViewModelStore())
        viewModel.placeTestCall()
        created.complete(Result.success(OutgoingCall("call1", videoLine = false)))
        runCurrent()

        viewModel.placeTestCall()
        runCurrent()

        coVerify(exactly = 1) { callRepository.createCall(CALLEE, any()) }
    }

    @Test
    fun `a failed create ends the call screen and says so`() = runTest {
        val viewModel = viewModelIn(ViewModelStore())
        viewModel.placeTestCall()

        created.complete(Result.failure(Exception("offline")))
        advanceUntilIdle()

        assertEquals(EndReason.ERROR, (holder.callState.value as CallState.Ended).reason)
        assertEquals(Unit, viewModel.setupFailed.first())
    }

    @Test
    fun `a failed create leaves alone a call that started ringing during the setup`() = runTest {
        viewModelIn(ViewModelStore()).placeTestCall()
        runCurrent()
        val ringing = CallState.IncomingRinging("call9", "u3", "Bob", null)
        holder.updateState(ringing)

        created.complete(Result.failure(Exception("offline")))
        advanceUntilIdle()

        assertEquals(ringing, holder.callState.value)
    }

    @Test
    fun `a create that fails after the screen closed leaves the next screen alone`() = runTest {
        val store = ViewModelStore()
        viewModelIn(store).placeTestCall()
        runCurrent()

        store.clear()
        holder.prepareOutgoingCall()
        val next = holder.startPlacing("u3", "Bob", null)
        created.complete(Result.failure(Exception("offline")))
        advanceUntilIdle()

        assertEquals(next, holder.callState.value)
    }

    @Test
    fun `cancelling while the call is created ends it instead of starting it`() = runTest {
        val viewModel = viewModelIn(ViewModelStore())
        viewModel.placeTestCall()
        runCurrent()

        viewModel.hangup()
        created.complete(Result.success(OutgoingCall("call1", videoLine = false)))
        advanceUntilIdle()

        assertEquals(CallState.Ended("", EndReason.HANGUP), holder.callState.value)
        assertNull(shadowOf(app).nextStartedService)
        coVerify { callRepository.endCall("call1", EndReason.HANGUP) }
        // Its push has rung the callee, so the chat records the call.
        coVerify { callRepository.logCallMessage("chat1", EndReason.HANGUP, 0, false) }
    }

    @Test
    fun `a call the service takes over is left to the service`() = runTest {
        viewModelIn(ViewModelStore()).placeTestCall()
        created.complete(Result.success(OutgoingCall("call1", videoLine = false)))
        runCurrent()
        val ringing = CallState.OutgoingRinging("call1", CALLEE, "Alice", null)
        holder.takeOverPlacing(ringing)

        advanceTimeBy(CallViewModel.HANDOVER_TIMEOUT_MS * 2)
        runCurrent()

        assertEquals(ringing, holder.callState.value)
        coVerify(exactly = 0) { callRepository.endCall(any(), any()) }
        coVerify(exactly = 0) { callRepository.logCallMessage(any(), any(), any(), any()) }
    }

    @Test
    fun `a call the service never takes over is ended`() = runTest {
        viewModelIn(ViewModelStore()).placeTestCall()
        created.complete(Result.success(OutgoingCall("call1", videoLine = false)))
        runCurrent()
        assertEquals("call1", (holder.callState.value as CallState.Placing).callId)

        advanceTimeBy(CallViewModel.HANDOVER_TIMEOUT_MS)
        runCurrent()

        assertEquals(CallState.Ended("call1", EndReason.ERROR), holder.callState.value)
        coVerify { callRepository.endCall("call1", EndReason.ERROR) }
        coVerify { callRepository.logCallMessage("chat1", EndReason.ERROR, 0, false) }
    }

    @Test
    fun `a create that takes too long fails the placing`() = runTest {
        val viewModel = viewModelIn(ViewModelStore())
        viewModel.placeTestCall()

        advanceTimeBy(CallViewModel.CREATE_TIMEOUT_MS)
        runCurrent()

        assertEquals(CallState.Ended("", EndReason.ERROR), holder.callState.value)
        assertEquals(Unit, viewModel.setupFailed.first())
    }

    @Test
    fun `cancelling after the call was created ends and records it once`() = runTest {
        // The document exists and its push has rung the callee, but no service holds the call yet.
        val viewModel = viewModelIn(ViewModelStore())
        viewModel.placeTestCall()
        created.complete(Result.success(OutgoingCall("call1", videoLine = false)))
        runCurrent()

        viewModel.hangup()
        advanceTimeBy(CallViewModel.HANDOVER_TIMEOUT_MS * 2)
        runCurrent()

        assertEquals(CallState.Ended("call1", EndReason.HANGUP), holder.callState.value)
        coVerify(exactly = 1) { callRepository.endCall("call1", any()) }
        coVerify(exactly = 1) { callRepository.logCallMessage(any(), any(), any(), any()) }
        coVerify { callRepository.logCallMessage("chat1", EndReason.HANGUP, 0, false) }
    }

    @Test
    fun `a create that lands after its timeout is ended and recorded`() = runTest {
        val viewModel = viewModelIn(ViewModelStore())
        viewModel.placeTestCall()
        advanceTimeBy(CallViewModel.CREATE_TIMEOUT_MS)
        runCurrent()
        assertEquals(CallState.Ended("", EndReason.ERROR), holder.callState.value)

        created.complete(Result.success(OutgoingCall("call1", videoLine = false)))
        runCurrent()

        assertNull(shadowOf(app).nextStartedService)
        coVerify { callRepository.endCall("call1", EndReason.ERROR) }
        coVerify { callRepository.logCallMessage("chat1", EndReason.ERROR, 0, false) }
    }

    @Test
    fun `a create that lands after Cancel and the timeout is ended as a hang-up`() = runTest {
        val viewModel = viewModelIn(ViewModelStore())
        viewModel.placeTestCall()
        runCurrent()
        viewModel.hangup()
        advanceTimeBy(CallViewModel.CREATE_TIMEOUT_MS)
        runCurrent()

        created.complete(Result.success(OutgoingCall("call1", videoLine = false)))
        runCurrent()

        assertEquals(CallState.Ended("", EndReason.HANGUP), holder.callState.value)
        coVerify { callRepository.endCall("call1", EndReason.HANGUP) }
        coVerify(exactly = 1) { callRepository.logCallMessage("chat1", EndReason.HANGUP, 0, false) }
        assertNull(withTimeoutOrNull(1_000) { viewModel.setupFailed.first() })
    }

    @Test
    fun `a failure after Cancel does not say the call could not start`() = runTest {
        val viewModel = viewModelIn(ViewModelStore())
        viewModel.placeTestCall()
        runCurrent()

        viewModel.hangup()
        created.complete(Result.failure(Exception("offline")))
        runCurrent()

        assertEquals(CallState.Ended("", EndReason.HANGUP), holder.callState.value)
        assertNull(withTimeoutOrNull(1_000) { viewModel.setupFailed.first() })
    }

    @Test
    fun `a refused service start ends the placing even when the network never answers`() = runTest {
        // An end written offline completes only when the server acknowledges it. The placing must
        // end first, or it would stay up and block every call until then.
        coEvery { callRepository.endCall(any(), any()) } coAnswers { awaitCancellation() }
        val background = object : ContextWrapper(app) {
            override fun startForegroundService(service: Intent?): ComponentName =
                throw IllegalStateException("not allowed from the background")
        }
        val viewModel = viewModelIn(ViewModelStore(), context = background)
        viewModel.placeTestCall()

        created.complete(Result.success(OutgoingCall("call1", videoLine = false)))
        runCurrent()

        assertEquals(CallState.Ended("call1", EndReason.ERROR), holder.callState.value)
    }

    @Test
    fun `a cancelled call's late create leaves the next call alone`() = runTest {
        val toAlice = CompletableDeferred<Result<OutgoingCall>>()
        coEvery { callRepository.createCall("alice", any()) } coAnswers { toAlice.await() }
        coEvery { callRepository.createCall("bob", any()) } coAnswers { awaitCancellation() }
        val viewModel = viewModelIn(ViewModelStore())
        viewModel.placeTestCall(callee = "alice")
        runCurrent()
        viewModel.hangup()

        viewModel.placeTestCall(callee = "bob")
        runCurrent()
        val toBob = holder.callState.value
        toAlice.complete(Result.success(OutgoingCall("callAlice", videoLine = false)))
        runCurrent()

        assertEquals("bob", (toBob as CallState.Placing).calleeId)
        assertEquals(toBob, holder.callState.value)
        coVerify { callRepository.endCall("callAlice", EndReason.HANGUP) }
    }

    private companion object {
        const val CALLEE = "u2"
    }
}
