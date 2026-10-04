package com.firestream.chat.ui.call

import com.firestream.chat.domain.model.OutgoingCall
import com.firestream.chat.domain.repository.CallRepository
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class OutgoingCallPlacerTest {

    private val repository = mockk<CallRepository>(relaxed = true)
    private val started = mutableListOf<Pair<PlacingCall, OutgoingCall>>()
    private val request = PlacingCall("callee1", "Alice", null, "chat1", video = true)
    private val created = OutgoingCall("call1", videoLine = true)

    // A scope of its own on the test's scheduler: a wait that never ends must not fail the test,
    // and `backgroundScope` would not run under `advanceUntilIdle`.
    private fun TestScope.placer(startCall: (PlacingCall, OutgoingCall) -> Unit = { r, c -> started += r to c }) =
        OutgoingCallPlacer(repository, CoroutineScope(StandardTestDispatcher(testScheduler) + SupervisorJob()), startCall)

    @Test
    fun `the call shows as being placed until the service has it`() = runTest {
        val creation = CompletableDeferred<Result<OutgoingCall>>()
        coEvery { repository.createCall("callee1", true) } coAnswers { creation.await() }
        val placer = placer()

        assertTrue(placer.place(request))
        advanceUntilIdle()
        assertEquals(request, placer.placing.value)
        assertTrue(started.isEmpty())

        creation.complete(Result.success(created))
        advanceUntilIdle()

        assertNull(placer.placing.value)
        assertEquals(listOf(request to created), started)
    }

    @Test
    fun `a second call is refused while one is being placed`() = runTest {
        coEvery { repository.createCall(any(), any()) } coAnswers { CompletableDeferred<Result<OutgoingCall>>().await() }
        val placer = placer()
        placer.place(request)

        assertFalse(placer.place(request.copy(calleeId = "callee2")))
        assertEquals(request, placer.placing.value)
    }

    @Test
    fun `a call that cannot be created is marked as failed and starts nothing`() = runTest {
        coEvery { repository.createCall(any(), any()) } returns Result.failure(IllegalStateException("offline"))
        val placer = placer()

        placer.place(request)
        advanceUntilIdle()

        assertEquals(request.copy(failed = true), placer.placing.value)
        assertTrue(started.isEmpty())
    }

    @Test
    fun `a new call can be placed after a failed one`() = runTest {
        coEvery { repository.createCall(any(), any()) } returns Result.failure(IllegalStateException("offline"))
        val placer = placer()
        placer.place(request)
        advanceUntilIdle()

        coEvery { repository.createCall(any(), any()) } returns Result.success(created)
        assertTrue(placer.place(request))
        advanceUntilIdle()

        assertNull(placer.placing.value)
        assertEquals(listOf(request to created), started)
    }

    // The callee's phone rings from the moment the call document exists. A hang-up during the
    // wait must not leave it ringing.
    @Test
    fun `a hang-up during the wait ends the call that is created after it`() = runTest {
        val creation = CompletableDeferred<Result<OutgoingCall>>()
        coEvery { repository.createCall(any(), any()) } coAnswers { creation.await() }
        val placer = placer()
        placer.place(request)
        advanceUntilIdle()

        placer.cancel()
        creation.complete(Result.success(created))
        advanceUntilIdle()

        assertTrue(started.isEmpty())
        assertNull(placer.placing.value)
        coVerify { repository.endCall("call1", "hangup") }
    }

    @Test
    fun `a service that cannot be started ends the call and marks it as failed`() = runTest {
        coEvery { repository.createCall(any(), any()) } returns Result.success(created)
        val placer = placer(startCall = { _, _ -> throw IllegalStateException("not in the foreground") })

        placer.place(request)
        advanceUntilIdle()

        assertEquals(request.copy(failed = true), placer.placing.value)
        coVerify { repository.endCall("call1", "error") }
    }
}
