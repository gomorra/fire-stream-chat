package com.firestream.chat.ui.call

import android.app.Application
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import androidx.test.core.app.ApplicationProvider
import com.firestream.chat.data.call.CallService
import com.firestream.chat.data.call.CallStateHolder
import com.firestream.chat.domain.model.CallState
import com.firestream.chat.domain.model.EndReason
import com.firestream.chat.domain.repository.CallRepository
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
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
    private val created = CompletableDeferred<Result<String>>()

    @Before
    fun setUp() {
        coEvery { callRepository.createCall(CALLEE) } coAnswers { created.await() }
        coEvery { callRepository.endCall(any(), any()) } returns Result.success(Unit)
    }

    /**
     * A ViewModel held by [store], as the call screen's activity holds it. A rotation keeps the
     * store; clearing it is the screen closing for good.
     *
     * The test scope stands in for the application scope. `backgroundScope` would not do:
     * `advanceUntilIdle()` leaves its work unrun.
     */
    private fun TestScope.viewModelIn(store: ViewModelStore): CallViewModel {
        val factory = viewModelFactory {
            initializer { CallViewModel(holder, callRepository, this@viewModelIn, app) }
        }
        return ViewModelProvider(store, factory)[CallViewModel::class.java]
    }

    /** Place a call as the call screen does: publish the placing, and set the call up only if that worked. */
    private fun CallViewModel.placeTestCall() {
        if (holder.prepareOutgoingCall(CALLEE, "Alice", null)) placeCall(CALLEE, "chat1", "Alice", null)
    }

    @Test
    fun `a created call is handed to the call service`() = runTest {
        viewModelIn(ViewModelStore()).placeTestCall()

        created.complete(Result.success("call1"))
        advanceUntilIdle()

        val started = shadowOf(app).nextStartedService
        assertEquals(CallService.ACTION_START_OUTGOING, started?.action)
        assertEquals("call1", started?.getStringExtra(CallService.EXTRA_CALL_ID))
    }

    @Test
    fun `closing the screen during setup ends the call instead of starting it`() = runTest {
        val store = ViewModelStore()
        viewModelIn(store).placeTestCall()
        runCurrent()

        store.clear()
        created.complete(Result.success("call1"))
        advanceUntilIdle()

        assertNull(shadowOf(app).nextStartedService)
        coVerify { callRepository.endCall("call1", "hangup") }
    }

    @Test
    fun `a screen that is closing ends the call before its ViewModel is cleared`() = runTest {
        // Back finishes the activity at once, but the system can hold back its onDestroy, and
        // with it onCleared, for seconds.
        val viewModel = viewModelIn(ViewModelStore())
        viewModel.placeTestCall()
        runCurrent()

        viewModel.onScreenClosed()
        created.complete(Result.success("call1"))
        advanceUntilIdle()

        assertNull(shadowOf(app).nextStartedService)
        coVerify { callRepository.endCall("call1", "hangup") }
    }

    @Test
    fun `a second request during setup places one call`() = runTest {
        val viewModel = viewModelIn(ViewModelStore())
        viewModel.placeTestCall()
        viewModel.placeTestCall()

        created.complete(Result.success("call1"))
        advanceUntilIdle()

        coVerify(exactly = 1) { callRepository.createCall(CALLEE) }
    }

    @Test
    fun `a second request before the service has taken the call places one call`() = runTest {
        // The service takes the call over on the main thread a moment after it is handed over,
        // and the call is still being placed until then.
        val viewModel = viewModelIn(ViewModelStore())
        viewModel.placeTestCall()
        created.complete(Result.success("call1"))
        runCurrent()

        viewModel.placeTestCall()
        runCurrent()

        coVerify(exactly = 1) { callRepository.createCall(CALLEE) }
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
        val next = CallState.Placing("u3", "Bob", null)
        holder.prepareOutgoingCall("u3", "Bob", null)
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
        created.complete(Result.success("call1"))
        advanceUntilIdle()

        assertEquals(CallState.Ended("", EndReason.HANGUP), holder.callState.value)
        assertNull(shadowOf(app).nextStartedService)
        coVerify { callRepository.endCall("call1", "hangup") }
    }

    @Test
    fun `a call the service takes over is left to the service`() = runTest {
        viewModelIn(ViewModelStore()).placeTestCall()
        created.complete(Result.success("call1"))
        runCurrent()
        val ringing = CallState.OutgoingRinging("call1", CALLEE, "Alice", null)
        holder.takeOverPlacing(ringing)

        advanceTimeBy(CallViewModel.HANDOVER_TIMEOUT_MS * 2)
        runCurrent()

        assertEquals(ringing, holder.callState.value)
        coVerify(exactly = 0) { callRepository.endCall(any(), any()) }
    }

    @Test
    fun `a call the service never takes over is ended`() = runTest {
        viewModelIn(ViewModelStore()).placeTestCall()
        created.complete(Result.success("call1"))
        runCurrent()
        assertEquals(CallState.Placing(CALLEE, "Alice", null, callId = "call1"), holder.callState.value)

        advanceTimeBy(CallViewModel.HANDOVER_TIMEOUT_MS)
        runCurrent()

        assertEquals(CallState.Ended("call1", EndReason.ERROR), holder.callState.value)
        coVerify { callRepository.endCall("call1", "error") }
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

    private companion object {
        const val CALLEE = "u2"
    }
}
