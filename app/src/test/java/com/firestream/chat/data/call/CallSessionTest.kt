package com.firestream.chat.data.call

import com.firestream.chat.domain.model.CallSignalingData
import com.firestream.chat.domain.model.CallState
import com.firestream.chat.domain.model.EndReason
import com.firestream.chat.domain.model.IceCandidateData
import com.firestream.chat.domain.model.SdpData
import com.firestream.chat.domain.repository.CallRepository
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * The transitions of one call, without Android or WebRTC. Every scope shares the test's
 * dispatcher, so virtual time drives the ring timeout and the call's clock alike.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class CallSessionTest {

    private val repository = mockk<CallRepository>()
    private val stateHolder = CallStateHolder()
    private val host = FakeCallHost()

    private val callDocument = MutableSharedFlow<CallSignalingData>(extraBufferCapacity = 8)
    private val calleeCandidates = MutableSharedFlow<List<IceCandidateData>>(extraBufferCapacity = 8)
    private val callerCandidates = MutableSharedFlow<List<IceCandidateData>>(extraBufferCapacity = 8)

    /**
     * Root scopes that share the test dispatcher. `backgroundScope` would not do:
     * `advanceUntilIdle()` and `runCurrent()` leave its work unrun.
     */
    private val scopes = mutableListOf<CoroutineScope>()

    @Before
    fun setUp() {
        coEvery { repository.sendOffer(any(), any()) } returns Result.success(Unit)
        coEvery { repository.sendAnswerAndAccept(any(), any()) } returns Result.success(Unit)
        coEvery { repository.sendIceCandidate(any(), any(), any()) } returns Result.success(Unit)
        coEvery { repository.endCall(any(), any()) } returns Result.success(Unit)
        coEvery { repository.declineCall(any()) } returns Result.success(Unit)
        coEvery { repository.logCallMessage(any(), any(), any()) } returns Result.success(Unit)
        coEvery { repository.getCallById(CALL) } returns Result.success(doc("ringing"))
        every { repository.observeCallDocument(CALL) } returns callDocument
        every { repository.observeIceCandidates(CALL, "calleeCandidates") } returns calleeCandidates
        every { repository.observeIceCandidates(CALL, "callerCandidates") } returns callerCandidates
    }

    @After
    fun tearDown() {
        scopes.forEach { it.cancel() }
    }

    private fun TestScope.rootScope() = CoroutineScope(coroutineContext + SupervisorJob()).also { scopes += it }

    private fun TestScope.newSession(isCaller: Boolean) = CallSession(
        callId = CALL,
        isCaller = isCaller,
        chatId = if (isCaller) CHAT else null,
        remoteUserId = "u2",
        remoteName = "Alice",
        remoteAvatarUrl = null,
        repository = repository,
        stateHolder = stateHolder,
        host = host,
        parentScope = rootScope(),
        appScope = rootScope(),
        clock = { testScheduler.currentTime },
    )

    private fun doc(status: String, answer: SdpData? = null, endReason: String? = null) = CallSignalingData(
        callId = CALL,
        callerId = "me",
        calleeId = "u2",
        status = status,
        offer = OFFER,
        answer = answer,
        createdAt = 0,
        endedAt = null,
        endReason = endReason,
    )

    private val state get() = stateHolder.callState.value

    /** An outgoing call the callee has answered, with the answer applied. */
    private fun TestScope.answeredOutgoing(): Pair<CallSession, FakeCallMedia> {
        val session = newSession(isCaller = true)
        session.startOutgoing()
        runCurrent()
        callDocument.tryEmit(doc("answered", answer = ANSWER))
        runCurrent()
        val media = host.media.single()
        media.remoteDescriptions.single().onSet()
        runCurrent()
        return session to media
    }

    /** An outgoing call that has connected. */
    private fun TestScope.connectedOutgoing(): Pair<CallSession, FakeCallMedia> =
        answeredOutgoing().also { (_, media) ->
            media.listener.onConnectionChange(ConnectionChange.CONNECTED)
            runCurrent()
        }

    // ──────────────────────────────────────────────────────────────────────────
    // Outgoing
    // ──────────────────────────────────────────────────────────────────────────

    @Test
    fun `placing a call rings the callee and sends the offer`() = runTest {
        newSession(isCaller = true).startOutgoing()
        runCurrent()

        assertEquals(CallState.OutgoingRinging(CALL, "u2", "Alice", null, null), state)
        assertEquals(listOf("outgoing"), host.foreground)
        val media = host.media.single()
        assertEquals(true, media.microphoneEnabled)

        media.offer!!.onCreated(OFFER)
        runCurrent()

        coVerify { repository.sendOffer(CALL, OFFER) }
    }

    @Test
    fun `an answer connects the caller and adds each of the callee's candidates once`() = runTest {
        val (_, media) = answeredOutgoing()

        assertTrue(state is CallState.Connecting)
        assertEquals(listOf("outgoing", "show-ongoing"), host.foreground)
        assertEquals(ANSWER, media.remoteDescriptions.single().sdp)

        calleeCandidates.tryEmit(listOf(CANDIDATE_1))
        runCurrent()
        calleeCandidates.tryEmit(listOf(CANDIDATE_1, CANDIDATE_2))
        runCurrent()

        assertEquals(listOf(CANDIDATE_1, CANDIDATE_2), media.addedCandidates)
    }

    @Test
    fun `the call's clock starts at the first connect and survives a reconnect`() = runTest {
        val (_, media) = answeredOutgoing()
        advanceTimeBy(5_000)

        media.listener.onConnectionChange(ConnectionChange.CONNECTED)
        val startTime = (state as CallState.Connected).startTime
        advanceTimeBy(10_000)
        media.listener.onConnectionChange(ConnectionChange.DISCONNECTED)
        media.listener.onConnectionChange(ConnectionChange.CONNECTED)

        assertEquals(startTime, (state as CallState.Connected).startTime)
        assertTrue(host.audioSessionStarts > 0)
    }

    @Test
    fun `local candidates go to the caller's subcollection`() = runTest {
        newSession(isCaller = true).startOutgoing()
        runCurrent()

        host.media.single().listener.onLocalIceCandidate(CANDIDATE_1)
        runCurrent()

        coVerify { repository.sendIceCandidate(CALL, true, CANDIDATE_1) }
    }

    @Test
    fun `a call nobody answers ends as a timeout after 30 s`() = runTest {
        val session = newSession(isCaller = true)
        session.startOutgoing()
        runCurrent()

        advanceTimeBy(CallSession.RING_TIMEOUT_MS - 1)
        runCurrent()
        assertTrue(state is CallState.OutgoingRinging)

        advanceTimeBy(1)
        runCurrent()

        assertEquals(CallState.Ended(CALL, EndReason.TIMEOUT), state)
        coVerify { repository.endCall(CALL, "timeout") }
        coVerify { repository.logCallMessage(CHAT, "timeout", 0) }
        assertTrue(host.media.single().disposed)
        assertEquals(listOf(session), host.finished)
        assertTrue(host.audioSessionStops > 0)
    }

    @Test
    fun `the ring timeout stops once the call is answered`() = runTest {
        newSession(isCaller = true).startOutgoing()
        runCurrent()
        advanceTimeBy(10_000)
        callDocument.tryEmit(doc("answered", answer = ANSWER))
        runCurrent()

        // Past the ring's deadline, before the connection's.
        advanceTimeBy(CallSession.RING_TIMEOUT_MS - 10_000)
        runCurrent()

        assertTrue(state is CallState.Connecting)
        coVerify(exactly = 0) { repository.endCall(any(), any()) }
    }

    @Test
    fun `hanging up records how long the call was connected`() = runTest {
        val (session, _) = connectedOutgoing()
        advanceTimeBy(65_000)

        session.hangup()
        runCurrent()

        assertEquals(CallState.Ended(CALL, EndReason.HANGUP), state)
        coVerify { repository.endCall(CALL, "hangup") }
        coVerify { repository.logCallMessage(CHAT, "hangup", 65) }
    }

    @Test
    fun `a decline ends the caller's call as declined and records it once`() = runTest {
        newSession(isCaller = true).startOutgoing()
        runCurrent()

        callDocument.tryEmit(doc("declined", endReason = "declined"))
        runCurrent()
        callDocument.tryEmit(doc("declined", endReason = "declined"))
        runCurrent()

        assertEquals(CallState.Ended(CALL, EndReason.DECLINED), state)
        coVerify(exactly = 1) { repository.logCallMessage(CHAT, "declined", 0) }
        coVerify(exactly = 0) { repository.endCall(any(), any()) }
    }

    @Test
    fun `the other phone hanging up ends the call as a remote hangup`() = runTest {
        connectedOutgoing()
        advanceTimeBy(12_000)

        callDocument.tryEmit(doc("ended", endReason = "hangup"))
        runCurrent()

        assertEquals(CallState.Ended(CALL, EndReason.REMOTE_HANGUP), state)
        coVerify { repository.logCallMessage(CHAT, "remote_hangup", 12) }
        coVerify(exactly = 0) { repository.endCall(any(), any()) }
    }

    @Test
    fun `a failed connection ends the call as an error`() = runTest {
        val (_, media) = connectedOutgoing()

        media.listener.onConnectionChange(ConnectionChange.FAILED)
        runCurrent()

        assertEquals(CallState.Ended(CALL, EndReason.ERROR), state)
        coVerify { repository.endCall(CALL, "error") }
    }

    @Test
    fun `an offer that cannot be created ends the call as an error`() = runTest {
        newSession(isCaller = true).startOutgoing()
        runCurrent()

        host.media.single().offer!!.onFailure("no audio")
        runCurrent()

        assertEquals(CallState.Ended(CALL, EndReason.ERROR), state)
        coVerify { repository.endCall(CALL, "error") }
        coVerify { repository.logCallMessage(CHAT, "error", 0) }
    }

    @Test
    fun `a finished call ignores late events`() = runTest {
        val (session, media) = connectedOutgoing()
        session.hangup()
        runCurrent()
        val ended = state

        media.listener.onConnectionChange(ConnectionChange.CONNECTED)
        media.listener.onConnectionChange(ConnectionChange.FAILED)
        media.listener.onLocalIceCandidate(CANDIDATE_2)
        callDocument.tryEmit(doc("ended", endReason = "hangup"))
        advanceTimeBy(CallSession.RING_TIMEOUT_MS * 2)
        runCurrent()
        session.hangup()
        session.toggleMute()
        runCurrent()

        assertEquals(ended, state)
        coVerify(exactly = 1) { repository.endCall(any(), any()) }
        coVerify(exactly = 1) { repository.logCallMessage(any(), any(), any()) }
        coVerify(exactly = 0) { repository.sendIceCandidate(any(), any(), CANDIDATE_2) }
        assertEquals(listOf(session), host.finished)
    }

    @Test
    fun `a call that never connects after the answer ends as an error`() = runTest {
        answeredOutgoing()

        advanceTimeBy(CallSession.CONNECT_TIMEOUT_MS)
        runCurrent()

        assertEquals(CallState.Ended(CALL, EndReason.ERROR), state)
        coVerify { repository.endCall(CALL, "error") }
        coVerify { repository.logCallMessage(CHAT, "error", 0) }
    }

    @Test
    fun `a call answered here that never connects ends as an error`() = runTest {
        val session = newSession(isCaller = false)
        session.startIncoming()
        runCurrent()
        session.answer()
        runCurrent()

        advanceTimeBy(CallSession.CONNECT_TIMEOUT_MS)
        runCurrent()

        assertEquals(CallState.Ended(CALL, EndReason.ERROR), state)
        coVerify { repository.endCall(CALL, "error") }
    }

    @Test
    fun `a connection lost for too long ends the call as an error`() = runTest {
        val (_, media) = connectedOutgoing()
        advanceTimeBy(40_000)

        media.listener.onConnectionChange(ConnectionChange.DISCONNECTED)
        advanceTimeBy(CallSession.RECONNECT_TIMEOUT_MS)
        runCurrent()

        assertEquals(CallState.Ended(CALL, EndReason.ERROR), state)
        coVerify { repository.endCall(CALL, "error") }
        coVerify { repository.logCallMessage(CHAT, "error", 70) }
    }

    @Test
    fun `a connection that comes back in time goes on`() = runTest {
        val (_, media) = connectedOutgoing()

        media.listener.onConnectionChange(ConnectionChange.DISCONNECTED)
        advanceTimeBy(CallSession.RECONNECT_TIMEOUT_MS - 1)
        media.listener.onConnectionChange(ConnectionChange.CONNECTED)
        advanceTimeBy(CallSession.RECONNECT_TIMEOUT_MS * 10)
        runCurrent()

        assertTrue(state is CallState.Connected)
        coVerify(exactly = 0) { repository.endCall(any(), any()) }
    }

    @Test
    fun `the callee's ring timeout reads as a timeout for the caller`() = runTest {
        newSession(isCaller = true).startOutgoing()
        runCurrent()

        callDocument.tryEmit(doc("ended", endReason = "timeout"))
        runCurrent()

        assertEquals(CallState.Ended(CALL, EndReason.TIMEOUT), state)
        coVerify { repository.logCallMessage(CHAT, "timeout", 0) }
    }

    @Test
    fun `a repeated answer is applied once`() = runTest {
        val (_, media) = connectedOutgoing()

        callDocument.tryEmit(doc("answered", answer = ANSWER))
        runCurrent()

        assertEquals(1, media.remoteDescriptions.size)
        assertTrue(state is CallState.Connected)
    }

    @Test
    fun `mute reaches the microphone`() = runTest {
        val (session, media) = connectedOutgoing()

        session.toggleMute()
        assertTrue(stateHolder.uiControls.value.isMuted)
        assertEquals(false, media.microphoneEnabled)

        session.toggleMute()
        assertFalse(stateHolder.uiControls.value.isMuted)
        assertEquals(true, media.microphoneEnabled)
    }

    // ──────────────────────────────────────────────────────────────────────────
    // Incoming
    // ──────────────────────────────────────────────────────────────────────────

    @Test
    fun `an incoming call rings without the microphone`() = runTest {
        newSession(isCaller = false).startIncoming()
        runCurrent()

        assertEquals(CallState.IncomingRinging(CALL, "u2", "Alice", null, null), state)
        assertEquals(listOf("incoming"), host.foreground)
        assertTrue(host.media.isEmpty())
    }

    @Test
    fun `answering without the microphone permission does nothing`() = runTest {
        val session = newSession(isCaller = false)
        session.startIncoming()
        runCurrent()
        host.microphoneGranted = false

        session.answer()
        runCurrent()

        assertTrue(state is CallState.IncomingRinging)
        assertTrue(host.media.isEmpty())
        coVerify(exactly = 0) { repository.getCallById(any()) }
    }

    @Test
    fun `answering applies the offer, sends the answer, and adds the caller's candidates`() = runTest {
        val session = newSession(isCaller = false)
        session.startIncoming()
        runCurrent()

        session.answer()
        runCurrent()

        assertTrue(state is CallState.Connecting)
        assertEquals(listOf("incoming", "ongoing"), host.foreground)
        val media = host.media.single()
        val remote = media.remoteDescriptions.single()
        assertEquals(OFFER, remote.sdp)

        remote.onSet()
        media.answer!!.onCreated(ANSWER)
        runCurrent()
        callerCandidates.tryEmit(listOf(CANDIDATE_1))
        runCurrent()

        coVerify { repository.sendAnswerAndAccept(CALL, ANSWER) }
        assertEquals(listOf(CANDIDATE_1), media.addedCandidates)
    }

    @Test
    fun `answering a call the caller already ended shows it ended without answering`() = runTest {
        coEvery { repository.getCallById(CALL) } returns Result.success(doc("ended", endReason = "hangup"))
        val session = newSession(isCaller = false)
        session.startIncoming()
        runCurrent()

        session.answer()
        runCurrent()

        assertEquals(CallState.Ended(CALL, EndReason.REMOTE_HANGUP), state)
        assertTrue(session.isFinished)
        assertTrue(host.media.single().remoteDescriptions.isEmpty())
        coVerify(exactly = 0) { repository.sendAnswerAndAccept(any(), any()) }
    }

    @Test
    fun `a call is answered once`() = runTest {
        val session = newSession(isCaller = false)
        session.startIncoming()
        runCurrent()

        session.answer()
        session.answer()
        runCurrent()

        coVerify(exactly = 1) { repository.getCallById(CALL) }
        assertEquals(1, host.media.size)
    }

    @Test
    fun `declining tells the caller and ends the call`() = runTest {
        val session = newSession(isCaller = false)
        session.startIncoming()
        runCurrent()

        session.decline()
        runCurrent()

        assertEquals(CallState.Ended(CALL, EndReason.DECLINED), state)
        coVerify { repository.declineCall(CALL) }
        coVerify(exactly = 0) { repository.logCallMessage(any(), any(), any()) }
        assertEquals(listOf(session), host.finished)
    }

    @Test
    fun `an incoming call nobody answers times out without writing to the chat`() = runTest {
        newSession(isCaller = false).startIncoming()
        runCurrent()

        advanceTimeBy(CallSession.RING_TIMEOUT_MS)
        runCurrent()

        assertEquals(CallState.Ended(CALL, EndReason.TIMEOUT), state)
        coVerify { repository.endCall(CALL, "timeout") }
        coVerify(exactly = 0) { repository.logCallMessage(any(), any(), any()) }
    }

    private companion object {
        const val CALL = "call1"
        const val CHAT = "chat1"
        val OFFER = SdpData("v=0 offer", "offer")
        val ANSWER = SdpData("v=0 answer", "answer")
        val CANDIDATE_1 = IceCandidateData("audio", 0, "candidate:1")
        val CANDIDATE_2 = IceCandidateData("audio", 0, "candidate:2")
    }
}

/** Records what the session asks of Android. */
internal class FakeCallHost : CallHost {
    var microphoneGranted = true
    val foreground = mutableListOf<String>()
    val media = mutableListOf<FakeCallMedia>()
    var audioSessionStarts = 0
    var audioSessionStops = 0
    val finished = mutableListOf<CallSession>()

    override fun hasMicrophonePermission() = microphoneGranted
    override fun localAvatarPath(userId: String): String? = null
    override fun foregroundOutgoing(remoteName: String) { foreground += "outgoing" }
    override fun foregroundIncoming(remoteName: String) { foreground += "incoming" }
    override fun foregroundOngoing(remoteName: String) { foreground += "ongoing" }
    override fun showOngoing(remoteName: String) { foreground += "show-ongoing" }
    override fun createMedia(callId: String, listener: CallMedia.Listener) = FakeCallMedia(listener).also { media += it }
    override fun startAudioSession() { audioSessionStarts++ }
    override fun stopAudioSession() { audioSessionStops++ }
    override fun onSessionFinished(session: CallSession) { finished += session }
}

/**
 * Holds each request until the test completes it, as WebRTC completes them later on its own thread.
 * It does not drop callbacks after [dispose]: the session must ignore them itself.
 */
internal class FakeCallMedia(val listener: CallMedia.Listener) : CallMedia {
    class SdpRequest(val onCreated: (SdpData) -> Unit, val onFailure: (String?) -> Unit)
    class RemoteDescription(val sdp: SdpData, val onSet: () -> Unit, val onFailure: (String?) -> Unit)

    var offer: SdpRequest? = null
    var answer: SdpRequest? = null
    val remoteDescriptions = mutableListOf<RemoteDescription>()
    val addedCandidates = mutableListOf<IceCandidateData>()
    var microphoneEnabled: Boolean? = null
    var disposed = false

    override fun createLocalOffer(onCreated: (SdpData) -> Unit, onFailure: (String?) -> Unit) {
        offer = SdpRequest(onCreated, onFailure)
    }

    override fun createLocalAnswer(onCreated: (SdpData) -> Unit, onFailure: (String?) -> Unit) {
        answer = SdpRequest(onCreated, onFailure)
    }

    override fun setRemoteDescription(sdp: SdpData, onSet: () -> Unit, onFailure: (String?) -> Unit) {
        remoteDescriptions += RemoteDescription(sdp, onSet, onFailure)
    }

    override fun addIceCandidate(candidate: IceCandidateData) {
        addedCandidates += candidate
    }

    override fun setMicrophoneEnabled(enabled: Boolean) {
        microphoneEnabled = enabled
    }

    override fun dispose() {
        disposed = true
    }
}
