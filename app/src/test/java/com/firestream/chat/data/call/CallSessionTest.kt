package com.firestream.chat.data.call

import com.firestream.chat.data.remote.source.IceServerSource
import com.firestream.chat.domain.model.CallMedia
import com.firestream.chat.domain.model.CallSignalingData
import com.firestream.chat.domain.model.CallState
import com.firestream.chat.domain.model.CallSurface
import com.firestream.chat.domain.model.EndReason
import com.firestream.chat.domain.model.IceCandidateData
import com.firestream.chat.domain.model.IceServerData
import com.firestream.chat.domain.model.SdpData
import com.firestream.chat.domain.repository.CallRepository
import com.firestream.chat.test.fakes.FakeChatRepository
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * The transitions of one call, without Android or WebRTC. Every scope shares the test's
 * dispatcher, so virtual time drives the ring timeout and the call's clock alike.
 *
 * The connection itself is a [PeerSession], which has its own test. Here it is a fake that reports
 * events, and what the session hands it is checked: which side offers, whether the offer may carry
 * a video line, and a signalling that writes to this call.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class CallSessionTest {

    private val repository = mockk<CallRepository>()
    private val chatRepository = FakeChatRepository()
    private val stateHolder = CallStateHolder()
    private val host = FakeCallHost()

    private val callDocument = MutableSharedFlow<CallSignalingData>(extraBufferCapacity = 8)

    /** What the backend hands out for the relay. Empty means the fetch finds none. */
    private var relayServers = listOf(RELAY)
    private val iceSource = object : IceServerSource {
        override suspend fun fetchIceServers(): List<IceServerData> = relayServers
    }
    private lateinit var iceServers: IceServerProvider

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
        coEvery { repository.logCallMessage(any(), any(), any(), any()) } returns Result.success(Unit)
        coEvery { repository.setMedia(any(), any(), any()) } returns Result.success(Unit)
        coEvery { repository.getCallById(CALL) } returns Result.success(doc("ringing"))
        every { repository.observeCallDocument(CALL) } returns callDocument
        every { repository.observeIceCandidates(CALL, "calleeCandidates") } returns flowOf(listOf(CANDIDATE_1))
        every { repository.observeIceCandidates(CALL, "callerCandidates") } returns flowOf(listOf(CANDIDATE_2))
    }

    @After
    fun tearDown() {
        scopes.forEach { it.cancel() }
    }

    private fun TestScope.rootScope() = CoroutineScope(coroutineContext + SupervisorJob()).also { scopes += it }

    /** The placing of the outgoing call, which the call screen started. */
    private var placing: CallState.Placing? = null

    private fun TestScope.newSession(isCaller: Boolean, video: Boolean = false, videoLine: Boolean = false): CallSession {
        if (isCaller) {
            // The call screen starts the placing, and records its document, before the service
            // starts the call.
            placing = stateHolder.startPlacing("u2", "Alice", null, video)!!.also {
                stateHolder.placingCreated(it.placingId, CALL)
            }
        }
        iceServers = IceServerProvider(iceSource, rootScope()) { testScheduler.currentTime }
        return CallSession(
            callId = CALL,
            isCaller = isCaller,
            chatId = if (isCaller) CHAT else null,
            remoteUserId = "u2",
            remoteName = "Alice",
            remoteAvatarUrl = null,
            video = video,
            offerVideoLine = videoLine,
            repository = repository,
            chatRepository = chatRepository,
            iceServers = iceServers,
            stateHolder = stateHolder,
            host = host,
            parentScope = rootScope(),
            appScope = rootScope(),
            clock = { testScheduler.currentTime },
        )
    }

    private fun doc(
        status: String,
        answer: SdpData? = null,
        endReason: String? = null,
        offer: SdpData? = OFFER,
        video: Boolean = false,
        media: Map<String, CallMedia> = emptyMap(),
    ) = CallSignalingData(
        callId = CALL,
        callerId = "me",
        calleeId = "u2",
        status = status,
        offer = offer,
        answer = answer,
        createdAt = 0,
        endedAt = null,
        endReason = endReason,
        video = video,
        media = media,
    )

    private val state get() = stateHolder.callState.value
    private val controls get() = stateHolder.uiControls.value
    private val remote get() = stateHolder.participants.value.single()
    private val media get() = host.media.single()
    private val peer get() = media.peers.single()

    /** An outgoing call the callee has answered. */
    private fun TestScope.answeredOutgoing(video: Boolean = false, videoLine: Boolean = false): CallSession {
        val session = newSession(isCaller = true, video, videoLine)
        session.startOutgoing()
        runCurrent()
        callDocument.tryEmit(doc("answered", answer = ANSWER))
        runCurrent()
        return session
    }

    /** An outgoing call that has connected. With [videoLine] both sides have agreed on the video line. */
    private fun TestScope.connectedOutgoing(video: Boolean = false, videoLine: Boolean = false): CallSession =
        answeredOutgoing(video, videoLine).also {
            peer.emit(PeerSessionEvent.VideoLine(videoLine))
            peer.emit(PeerSessionEvent.Connected)
            runCurrent()
        }

    /** The stage comes on screen, or leaves it for longer than the hand-over to the docked card takes. */
    private fun TestScope.showStage(showing: Boolean) {
        stateHolder.setSurfaceShowing(CallSurface.STAGE, showing)
        if (!showing) advanceTimeBy(CallStateHolder.OFF_SCREEN_GRACE_MILLIS)
        runCurrent()
    }

    // ──────────────────────────────────────────────────────────────────────────
    // Outgoing
    // ──────────────────────────────────────────────────────────────────────────

    @Test
    fun `placing a call rings the callee and opens the connection that makes the offer`() = runTest {
        newSession(isCaller = true).startOutgoing()
        runCurrent()

        assertEquals(CallState.OutgoingRinging(CALL, "u2", "Alice", null, null), state)
        assertEquals(listOf("outgoing"), host.foreground)
        assertEquals(true, media.microphoneEnabled)
        assertEquals("u2", peer.remoteId)
        assertTrue(peer.offers)

        // The connection sends its offer through the signalling it was given.
        peer.signaling.sendOffer(OFFER)

        coVerify { repository.sendOffer(CALL, OFFER) }
    }

    @Test
    fun `the call's person and chat are published with it`() = runTest {
        newSession(isCaller = true).startOutgoing()
        runCurrent()

        assertEquals("u2", remote.id)
        assertEquals("Alice", remote.name)
        assertEquals(CHAT, stateHolder.chatId.value)
    }

    @Test
    fun `a call whose placing was cancelled is ended instead of rung`() = runTest {
        val session = newSession(isCaller = true)
        stateHolder.cancelPlacing(placing!!.placingId)

        session.startOutgoing()
        runCurrent()

        assertEquals(CallState.Ended(CALL, EndReason.HANGUP), state)
        // Started with startForegroundService(), the service enters the foreground all the same.
        assertEquals(listOf("outgoing"), host.foreground)
        assertTrue(host.media.isEmpty())
        assertTrue(stateHolder.participants.value.isEmpty())
        coVerify { repository.endCall(CALL, EndReason.HANGUP) }
        coVerify(exactly = 0) { repository.logCallMessage(any(), any(), any(), any()) }
        assertEquals(listOf(session), host.finished)
    }

    @Test
    fun `an answer moves the caller on to connecting`() = runTest {
        answeredOutgoing()

        assertTrue(state is CallState.Connecting)
        assertEquals(listOf("outgoing", "show-ongoing"), host.foreground)
        // The connection applies the answer and the callee's candidates itself, from this list.
        assertEquals(listOf(CANDIDATE_1), peer.signaling.observeCandidates().first())
    }

    @Test
    fun `the call's clock starts at the first connect and survives a reconnect`() = runTest {
        answeredOutgoing()
        advanceTimeBy(5_000)

        peer.emit(PeerSessionEvent.Connected)
        runCurrent()
        val startTime = (state as CallState.Connected).startTime
        assertTrue(remote.connected)
        advanceTimeBy(10_000)
        peer.emit(PeerSessionEvent.Disconnected)
        runCurrent()
        assertFalse(remote.connected)
        peer.emit(PeerSessionEvent.Connected)
        runCurrent()

        assertEquals(startTime, (state as CallState.Connected).startTime)
        assertTrue(remote.connected)
        assertTrue(host.audioSessionStarts > 0)
    }

    @Test
    fun `local candidates go to the caller's subcollection`() = runTest {
        newSession(isCaller = true).startOutgoing()
        runCurrent()

        peer.signaling.sendCandidate(CANDIDATE_1)

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
        coVerify { repository.endCall(CALL, EndReason.TIMEOUT) }
        coVerify { repository.logCallMessage(CHAT, EndReason.TIMEOUT, 0, false) }
        assertTrue(media.disposed)
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
        val session = connectedOutgoing()
        advanceTimeBy(65_000)

        session.hangup()
        runCurrent()

        assertEquals(CallState.Ended(CALL, EndReason.HANGUP), state)
        coVerify { repository.endCall(CALL, EndReason.HANGUP) }
        coVerify { repository.logCallMessage(CHAT, EndReason.HANGUP, 65, false) }
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
        coVerify(exactly = 1) { repository.logCallMessage(CHAT, EndReason.DECLINED, 0, false) }
        coVerify(exactly = 0) { repository.endCall(any(), any()) }
    }

    @Test
    fun `the other phone hanging up ends the call as a remote hangup`() = runTest {
        connectedOutgoing()
        advanceTimeBy(12_000)

        callDocument.tryEmit(doc("ended", endReason = "hangup"))
        runCurrent()

        assertEquals(CallState.Ended(CALL, EndReason.REMOTE_HANGUP), state)
        coVerify { repository.logCallMessage(CHAT, EndReason.REMOTE_HANGUP, 12, false) }
        coVerify(exactly = 0) { repository.endCall(any(), any()) }
    }

    @Test
    fun `a failed connection ends the call as an error`() = runTest {
        connectedOutgoing()

        peer.emit(PeerSessionEvent.Failed("ICE connection failed"))
        runCurrent()

        assertEquals(CallState.Ended(CALL, EndReason.ERROR), state)
        coVerify { repository.endCall(CALL, EndReason.ERROR) }
    }

    @Test
    fun `an offer that cannot be created ends the call as an error`() = runTest {
        newSession(isCaller = true).startOutgoing()
        runCurrent()

        peer.emit(PeerSessionEvent.Failed("create offer: no audio"))
        runCurrent()

        assertEquals(CallState.Ended(CALL, EndReason.ERROR), state)
        coVerify { repository.endCall(CALL, EndReason.ERROR) }
        coVerify { repository.logCallMessage(CHAT, EndReason.ERROR, 0, false) }
    }

    @Test
    fun `a finished call ignores late events`() = runTest {
        val session = connectedOutgoing(videoLine = true)
        val media = media
        val peer = peer
        session.hangup()
        runCurrent()
        val ended = state
        val controlsAtEnd = controls

        peer.emit(PeerSessionEvent.Connected)
        peer.emit(PeerSessionEvent.Failed("late"))
        media.listener.onCameraStarted(front = false)
        media.listener.onCameraFailed()
        callDocument.tryEmit(doc("ended", endReason = "hangup"))
        showStage(true)
        advanceTimeBy(CallSession.RING_TIMEOUT_MS * 2)
        runCurrent()
        session.hangup()
        session.toggleMute()
        session.setCamera(true)
        session.flipCamera()
        runCurrent()

        assertEquals(ended, state)
        assertEquals(controlsAtEnd, controls)
        assertTrue(media.cameraRequests.isEmpty())
        assertEquals(0, media.flips)
        coVerify(exactly = 1) { repository.endCall(any(), any()) }
        coVerify(exactly = 1) { repository.logCallMessage(any(), any(), any(), any()) }
        assertEquals(listOf(session), host.finished)
    }

    @Test
    fun `a call that never connects after the answer ends as an error`() = runTest {
        answeredOutgoing()

        advanceTimeBy(CallSession.CONNECT_TIMEOUT_MS)
        runCurrent()

        assertEquals(CallState.Ended(CALL, EndReason.ERROR), state)
        coVerify { repository.endCall(CALL, EndReason.ERROR) }
        coVerify { repository.logCallMessage(CHAT, EndReason.ERROR, 0, false) }
    }

    @Test
    fun `a call answered here that never connects ends as an error`() = runTest {
        val session = newSession(isCaller = false)
        session.startIncoming()
        runCurrent()
        session.answer(camera = false)
        runCurrent()

        advanceTimeBy(CallSession.CONNECT_TIMEOUT_MS)
        runCurrent()

        assertEquals(CallState.Ended(CALL, EndReason.ERROR), state)
        coVerify { repository.endCall(CALL, EndReason.ERROR) }
    }

    @Test
    fun `a connection lost for too long ends the call as an error`() = runTest {
        connectedOutgoing()
        advanceTimeBy(40_000)

        peer.emit(PeerSessionEvent.Disconnected)
        advanceTimeBy(CallSession.RECONNECT_TIMEOUT_MS)
        runCurrent()

        assertEquals(CallState.Ended(CALL, EndReason.ERROR), state)
        coVerify { repository.endCall(CALL, EndReason.ERROR) }
        coVerify { repository.logCallMessage(CHAT, EndReason.ERROR, 70, false) }
    }

    @Test
    fun `a connection that comes back in time goes on`() = runTest {
        connectedOutgoing()

        peer.emit(PeerSessionEvent.Disconnected)
        advanceTimeBy(CallSession.RECONNECT_TIMEOUT_MS - 1)
        peer.emit(PeerSessionEvent.Connected)
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
        coVerify { repository.logCallMessage(CHAT, EndReason.TIMEOUT, 0, false) }
    }

    // The connection applies the answer once: PeerSessionTest. Here, a snapshot that still says
    // "answered" must not move a connected call, nor start the connect timeout again.
    @Test
    fun `a repeated answer changes nothing`() = runTest {
        connectedOutgoing()

        callDocument.tryEmit(doc("answered", answer = ANSWER))
        runCurrent()
        advanceTimeBy(CallSession.CONNECT_TIMEOUT_MS)
        runCurrent()

        assertTrue(state is CallState.Connected)
        assertEquals(listOf("outgoing", "show-ongoing"), host.foreground)
        assertEquals(1, media.peers.size)
        coVerify(exactly = 0) { repository.endCall(any(), any()) }
    }

    // The connection and the session watch the same document. The connection can report connected
    // before the session's snapshot arrives.
    @Test
    fun `an answer that arrives after the connect does not take the call back`() = runTest {
        newSession(isCaller = true).startOutgoing()
        runCurrent()

        peer.emit(PeerSessionEvent.Connected)
        runCurrent()
        callDocument.tryEmit(doc("answered", answer = ANSWER))
        runCurrent()
        advanceTimeBy(CallSession.CONNECT_TIMEOUT_MS)
        runCurrent()

        assertTrue(state is CallState.Connected)
        // The notification left "Calling…" when the call connected.
        assertEquals(listOf("outgoing", "show-ongoing"), host.foreground)
        coVerify(exactly = 0) { repository.endCall(any(), any()) }
    }

    @Test
    fun `mute reaches the microphone`() = runTest {
        val session = connectedOutgoing()

        session.toggleMute()
        assertTrue(controls.isMuted)
        assertEquals(false, media.microphoneEnabled)

        session.toggleMute()
        assertFalse(controls.isMuted)
        assertEquals(true, media.microphoneEnabled)
    }

    @Test
    fun `the caller builds its connection on the relay's servers that placing the call fetched`() = runTest {
        val session = newSession(isCaller = true)
        // CallRepository.createCall fetches them before the call document exists.
        iceServers.get()

        session.startOutgoing()
        runCurrent()

        assertEquals(listOf(RELAY), peer.iceServers)
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
        assertEquals("u2", remote.id)
    }

    @Test
    fun `answering without the microphone permission does nothing`() = runTest {
        val session = newSession(isCaller = false)
        session.startIncoming()
        runCurrent()
        host.microphoneGranted = false

        session.answer(camera = false)
        runCurrent()

        assertTrue(state is CallState.IncomingRinging)
        assertTrue(host.media.isEmpty())
        coVerify(exactly = 0) { repository.getCallById(any()) }
    }

    @Test
    fun `answering reads the offer and opens the connection that answers it`() = runTest {
        val session = newSession(isCaller = false)
        session.startIncoming()
        runCurrent()

        session.answer(camera = false)
        runCurrent()

        assertTrue(state is CallState.Connecting)
        assertEquals(listOf("incoming", "ongoing"), host.foreground)
        assertFalse(peer.offers)
        // The side that answers takes the video line the offer brings, and offers none itself.
        assertFalse(peer.offerVideoLine)
        // The ring fetched the relay's servers.
        assertEquals(listOf(RELAY), peer.iceServers)
        assertEquals(OFFER, peer.signaling.observeOffer().first())
        assertEquals(listOf(CANDIDATE_2), peer.signaling.observeCandidates().first())

        peer.signaling.sendAnswer(ANSWER)

        // The answer and "answered" go in one write.
        coVerify { repository.sendAnswerAndAccept(CALL, ANSWER) }
    }

    @Test
    fun `answering a call the caller already ended shows it ended without answering`() = runTest {
        coEvery { repository.getCallById(CALL) } returns Result.success(doc("ended", endReason = "hangup"))
        val session = newSession(isCaller = false)
        session.startIncoming()
        runCurrent()

        session.answer(camera = false)
        runCurrent()

        assertEquals(CallState.Ended(CALL, EndReason.REMOTE_HANGUP), state)
        assertTrue(session.isFinished)
        assertTrue(media.peers.isEmpty())
        assertTrue(media.disposed)
        coVerify(exactly = 0) { repository.sendAnswerAndAccept(any(), any()) }
        coVerify(exactly = 0) { repository.endCall(any(), any()) }
    }

    @Test
    fun `a call document that cannot be read ends the answered call as an error`() = runTest {
        coEvery { repository.getCallById(CALL) } returns Result.failure(Exception("offline"))
        val session = newSession(isCaller = false)
        session.startIncoming()
        runCurrent()

        session.answer(camera = false)
        runCurrent()

        assertEquals(CallState.Ended(CALL, EndReason.ERROR), state)
        assertTrue(media.peers.isEmpty())
        coVerify { repository.endCall(CALL, EndReason.ERROR) }
    }

    @Test
    fun `a ringing call without an offer ends as an error when it is answered`() = runTest {
        coEvery { repository.getCallById(CALL) } returns Result.success(doc("ringing", offer = null))
        val session = newSession(isCaller = false)
        session.startIncoming()
        runCurrent()

        session.answer(camera = false)
        runCurrent()

        assertEquals(CallState.Ended(CALL, EndReason.ERROR), state)
        assertTrue(media.peers.isEmpty())
    }

    @Test
    fun `a call is answered once`() = runTest {
        val session = newSession(isCaller = false)
        session.startIncoming()
        runCurrent()

        session.answer(camera = false)
        session.answer(camera = false)
        runCurrent()

        coVerify(exactly = 1) { repository.getCallById(CALL) }
        assertEquals(1, host.media.size)
        assertEquals(1, media.peers.size)
    }

    @Test
    fun `a ring that found no relay is answered on STUN alone`() = runTest {
        relayServers = emptyList()
        val session = newSession(isCaller = false)
        session.startIncoming()
        runCurrent()

        session.answer(camera = false)
        runCurrent()

        // The fetch found none, so the call runs on STUN alone.
        assertEquals(IceServerProvider.STUN_ONLY, peer.iceServers)
    }

    @Test
    fun `the side that answers learns the call's chat for the dock and writes no call message`() = runTest {
        val session = newSession(isCaller = false)
        session.startIncoming()
        runCurrent()
        assertNull(stateHolder.chatId.value)

        session.answer(camera = false)
        runCurrent()

        assertEquals("chat-u2", stateHolder.chatId.value)

        session.hangup()
        runCurrent()

        coVerify(exactly = 0) { repository.logCallMessage(any(), any(), any(), any()) }
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
        coVerify(exactly = 0) { repository.logCallMessage(any(), any(), any(), any()) }
        assertEquals(listOf(session), host.finished)
    }

    @Test
    fun `an incoming call nobody answers times out without writing to the chat`() = runTest {
        newSession(isCaller = false).startIncoming()
        runCurrent()

        advanceTimeBy(CallSession.RING_TIMEOUT_MS)
        runCurrent()

        assertEquals(CallState.Ended(CALL, EndReason.TIMEOUT), state)
        coVerify { repository.endCall(CALL, EndReason.TIMEOUT) }
        coVerify(exactly = 0) { repository.logCallMessage(any(), any(), any(), any()) }
    }

    // ──────────────────────────────────────────────────────────────────────────
    // The call's kind
    // ──────────────────────────────────────────────────────────────────────────

    @Test
    fun `a call started as video rings, runs and is logged as one`() = runTest {
        val session = connectedOutgoing(video = true, videoLine = true)

        assertEquals(true, (state as CallState.Connected).video)
        assertEquals(listOf("outgoing:video", "show-ongoing:video"), host.foreground)
        // A video call that can carry video plays on the speaker by default.
        assertTrue(host.preferSpeaker)
        // Nobody's camera runs, so the proximity lock stays as it is for a voice call.
        assertFalse(host.videoShowing)

        session.hangup()
        runCurrent()

        coVerify { repository.logCallMessage(CHAT, EndReason.HANGUP, 0, true) }
    }

    @Test
    fun `the offer carries a video line only to a callee whose app takes one`() = runTest {
        newSession(isCaller = true, video = true, videoLine = true).startOutgoing()
        runCurrent()

        assertTrue(peer.offerVideoLine)
        assertTrue(controls.videoAvailable)
    }

    @Test
    fun `a video call to an app without video is a voice call on screen from the start`() = runTest {
        connectedOutgoing(video = true, videoLine = false)

        assertFalse(peer.offerVideoLine)
        assertFalse(controls.videoAvailable)
        // It starts on the earpiece.
        assertFalse(host.preferSpeaker)
        // Still started as video: that is what the call log says.
        assertEquals(true, (state as CallState.Connected).video)
    }

    @Test
    fun `the side that is called learns the call's kind from the call document`() = runTest {
        newSession(isCaller = false).startIncoming()
        runCurrent()

        callDocument.tryEmit(doc("ringing", video = true))
        runCurrent()
        callDocument.tryEmit(doc("ringing", video = true))
        runCurrent()

        assertEquals(true, (state as CallState.IncomingRinging).video)
        // The ring is posted again with the right title, once.
        assertEquals(listOf("incoming", "show-incoming:video"), host.foreground)
    }

    // ──────────────────────────────────────────────────────────────────────────
    // The camera
    // ──────────────────────────────────────────────────────────────────────────

    @Test
    fun `the camera goes on from a screen that shows the call, and the other side hears once it captures`() = runTest {
        val session = connectedOutgoing(videoLine = true)
        showStage(true)

        session.setCamera(true)
        runCurrent()

        assertEquals(listOf(true), media.cameraRequests)
        assertTrue(controls.cameraOn)
        assertFalse(controls.cameraPaused)
        assertEquals("camera-type:on", host.foreground.last())
        // Opening the camera takes a moment. Until then the other side is told nothing new.
        coVerify(exactly = 0) { repository.setMedia(CALL, true, any()) }

        media.listener.onCameraStarted(front = false)
        runCurrent()

        assertFalse(controls.frontCamera)
        coVerify { repository.setMedia(CALL, true, true) }
        // While video shows the proximity lock is off, and the call plays on the speaker.
        assertTrue(host.videoShowing)
        assertTrue(host.preferSpeaker)
    }

    @Test
    fun `the camera pauses while no screen shows the call and comes back with the screen`() = runTest {
        val session = connectedOutgoing(videoLine = true)
        showStage(true)
        session.setCamera(true)
        media.listener.onCameraStarted(front = true)
        runCurrent()

        showStage(false)

        assertEquals(listOf(true, false), media.cameraRequests)
        assertTrue(controls.cameraOn)
        assertTrue(controls.cameraPaused)
        // The other side is told the camera is off, and shows the avatar.
        coVerify { repository.setMedia(CALL, false, true) }
        assertFalse(host.videoShowing)

        showStage(true)

        assertEquals(listOf(true, false, true), media.cameraRequests)
        assertFalse(controls.cameraPaused)
    }

    @Test
    fun `a refused camera foreground type leaves the camera off and the call running`() = runTest {
        val session = connectedOutgoing(videoLine = true)
        showStage(true)
        host.cameraTypeAllowed = false

        session.setCamera(true)
        runCurrent()

        assertFalse(controls.cameraOn)
        assertTrue(media.cameraRequests.isEmpty())
        assertTrue(state is CallState.Connected)
    }

    @Test
    fun `without the camera permission the camera stays off`() = runTest {
        val session = connectedOutgoing(videoLine = true)
        showStage(true)
        host.cameraGranted = false

        session.setCamera(true)
        runCurrent()

        assertFalse(controls.cameraOn)
        assertTrue(media.cameraRequests.isEmpty())
    }

    // A released app that placed the call applies the answer again on every change of an answered
    // call document. CallMediaPublisherTest holds the rule, and this the wiring.
    @Test
    fun `a call without a video line switches no camera on and is never written to`() = runTest {
        val session = connectedOutgoing(videoLine = false)
        showStage(true)

        session.setCamera(true)
        session.toggleMute()
        runCurrent()

        assertFalse(controls.cameraOn)
        assertTrue(media.cameraRequests.isEmpty())
        coVerify(exactly = 0) { repository.setMedia(any(), any(), any()) }
    }

    @Test
    fun `a camera that fails switches off and the call goes on`() = runTest {
        val session = connectedOutgoing(videoLine = true)
        showStage(true)
        session.setCamera(true)
        media.listener.onCameraStarted(front = true)
        runCurrent()

        media.listener.onCameraFailed()
        runCurrent()

        assertFalse(controls.cameraOn)
        assertEquals("camera-type:off", host.foreground.last())
        assertFalse(host.videoShowing)
        assertTrue(state is CallState.Connected)
    }

    @Test
    fun `flipping the camera reaches it, and the screen learns which one is in use`() = runTest {
        val session = connectedOutgoing(videoLine = true)
        showStage(true)
        session.setCamera(true)
        media.listener.onCameraStarted(front = true)

        session.flipCamera()
        media.listener.onCameraFlipped(front = false)

        assertEquals(1, media.flips)
        assertFalse(controls.frontCamera)
    }

    @Test
    fun `the ring of a video call shows the own camera, until the other side turns out to take no video`() = runTest {
        showStage(true)
        newSession(isCaller = true, video = true, videoLine = true).startOutgoing()
        runCurrent()

        assertEquals(listOf(true), media.cameraRequests)
        assertTrue(controls.cameraOn)

        peer.emit(PeerSessionEvent.VideoLine(available = false))
        runCurrent()

        // A preview that went on running would look like a camera that is being sent.
        assertEquals(listOf(true, false), media.cameraRequests)
        assertFalse(controls.cameraOn)
        assertFalse(controls.videoAvailable)
    }

    @Test
    fun `no preview runs for a video call that is known to carry no video`() = runTest {
        showStage(true)
        newSession(isCaller = true, video = true, videoLine = false).startOutgoing()
        runCurrent()

        assertTrue(media.cameraRequests.isEmpty())
        assertFalse(controls.cameraOn)
    }

    @Test
    fun `an incoming video ring shows the own camera only on an unlocked phone`() = runTest {
        host.locked = true
        showStage(true)
        newSession(isCaller = false, video = true).startIncoming()
        runCurrent()

        assertTrue(host.media.isEmpty())
        assertFalse(controls.cameraOn)
    }

    @Test
    fun `a preview that ran while the call rang carries over into the answered call`() = runTest {
        showStage(true)
        val session = newSession(isCaller = false, video = true)
        session.startIncoming()
        runCurrent()
        assertEquals(listOf(true), media.cameraRequests)
        // The ring is a short service. It takes no camera type.
        assertEquals(listOf("incoming:video"), host.foreground)

        session.answer(camera = true)
        runCurrent()

        assertEquals(listOf(true), media.cameraRequests)
        assertEquals(listOf("incoming:video", "ongoing:video+camera"), host.foreground)
        assertTrue(controls.cameraOn)
    }

    @Test
    fun `answering voice only switches a running preview off`() = runTest {
        showStage(true)
        val session = newSession(isCaller = false, video = true)
        session.startIncoming()
        runCurrent()

        session.answer(camera = false)
        runCurrent()

        assertEquals(listOf(true, false), media.cameraRequests)
        assertEquals(listOf("incoming:video", "ongoing:video"), host.foreground)
        assertFalse(controls.cameraOn)
    }

    @Test
    fun `answering with video switches the camera on where no preview ran`() = runTest {
        // A locked phone shows no preview while it rings.
        host.locked = true
        showStage(true)
        val session = newSession(isCaller = false, video = true)
        session.startIncoming()
        runCurrent()

        session.answer(camera = true)
        runCurrent()

        assertEquals(listOf(true), media.cameraRequests)
        assertEquals(listOf("incoming:video", "ongoing:video+camera"), host.foreground)
    }

    @Test
    fun `an answer that is refused leaves the ring's preview as it is`() = runTest {
        showStage(true)
        val session = newSession(isCaller = false, video = true)
        session.startIncoming()
        runCurrent()
        host.microphoneGranted = false

        session.answer(camera = false)
        runCurrent()

        assertTrue(state is CallState.IncomingRinging)
        assertEquals(listOf(true), media.cameraRequests)
    }

    @Test
    fun `an answer whose camera type is refused goes on with the camera off`() = runTest {
        showStage(true)
        val session = newSession(isCaller = false, video = true)
        session.startIncoming()
        runCurrent()
        host.cameraTypeAllowed = false

        session.answer(camera = true)
        runCurrent()

        assertEquals(listOf("incoming:video", "ongoing:video"), host.foreground)
        assertFalse(controls.cameraOn)
        assertEquals(listOf(true, false), media.cameraRequests)
        assertTrue(state is CallState.Connecting)
    }

    // ──────────────────────────────────────────────────────────────────────────
    // The other side's camera and microphone
    // ──────────────────────────────────────────────────────────────────────────

    @Test
    fun `what the other side says about its camera and microphone reaches the participant`() = runTest {
        connectedOutgoing(videoLine = true)
        val theirs = mapOf("u2" to CallMedia(camera = true, mic = false))

        callDocument.tryEmit(doc("answered", answer = ANSWER, media = theirs))
        runCurrent()
        callDocument.tryEmit(doc("answered", answer = ANSWER, media = theirs))
        runCurrent()

        assertTrue(remote.cameraOn)
        assertFalse(remote.micOn)
        // Their video shows again only with a frame that arrived after the switch. Asked once.
        assertEquals(listOf("u2"), media.awaitedFrames)
        assertTrue(host.videoShowing)
        assertTrue(host.preferSpeaker)
    }

    @Test
    fun `someone who has written nothing reads as camera off and microphone on`() = runTest {
        connectedOutgoing(videoLine = true)
        callDocument.tryEmit(doc("answered", answer = ANSWER, media = mapOf("u2" to CallMedia(camera = true, mic = false))))
        runCurrent()

        callDocument.tryEmit(doc("answered", answer = ANSWER, media = mapOf("me" to CallMedia(camera = true))))
        runCurrent()

        assertFalse(remote.cameraOn)
        assertTrue(remote.micOn)
        assertFalse(host.videoShowing)
    }

    @Test
    fun `a first frame of the other side's video reaches the participant`() = runTest {
        connectedOutgoing(videoLine = true)

        media.framedIds.value = setOf("u2")
        runCurrent()

        assertTrue(remote.hasFrame)
    }

    // ──────────────────────────────────────────────────────────────────────────
    // The end of a call while the camera runs
    // ──────────────────────────────────────────────────────────────────────────

    @Test
    fun `a call that ends while the camera runs releases it and leaves no picture claimed`() = runTest {
        val session = connectedOutgoing(video = true, videoLine = true)
        showStage(true)
        session.setCamera(true)
        media.listener.onCameraStarted(front = true)
        media.framedIds.value = setOf("u2")
        runCurrent()
        val media = media

        callDocument.tryEmit(doc("ended", endReason = "hangup"))
        runCurrent()

        assertEquals(CallState.Ended(CALL, EndReason.REMOTE_HANGUP), state)
        // Releasing the media closes the camera with the connection.
        assertTrue(media.disposed)
        assertFalse(controls.cameraOn)
        assertFalse(controls.cameraPaused)
        assertFalse(remote.hasFrame)
        assertTrue(host.audioSessionStops > 0)
        coVerify { repository.logCallMessage(CHAT, EndReason.REMOTE_HANGUP, 0, true) }

        // Nothing is written to the call document of a call that is over.
        showStage(false)
        coVerify(exactly = 1) { repository.setMedia(CALL, false, any()) }
    }

    private companion object {
        const val CALL = "call1"
        const val CHAT = "chat1"
        val OFFER = SdpData("v=0 offer", "offer")
        val ANSWER = SdpData("v=0 answer", "answer")
        val CANDIDATE_1 = IceCandidateData("audio", 0, "candidate:1")
        val CANDIDATE_2 = IceCandidateData("audio", 0, "candidate:2")
        val RELAY = IceServerData(listOf("turn:relay.example:3478"), "user", "secret")
    }
}

/**
 * Records what the session asks of Android. A foreground entry of a call started as video ends in
 * `:video`, and one that takes the camera type in `+camera`.
 */
internal class FakeCallHost : CallHost {
    var microphoneGranted = true
    var cameraGranted = true
    var locked = false

    /** Whether the system lets the service name the camera in its foreground type. */
    var cameraTypeAllowed = true
    val foreground = mutableListOf<String>()
    val media = mutableListOf<FakeCallLocalMedia>()
    var audioSessionStarts = 0
    var audioSessionStops = 0
    var preferSpeaker = false
    var videoShowing = false
    val finished = mutableListOf<CallSession>()

    private fun kind(video: Boolean) = if (video) ":video" else ""

    override fun hasMicrophonePermission() = microphoneGranted
    override fun hasCameraPermission() = cameraGranted
    override fun isLocked() = locked
    override fun localAvatarPath(userId: String): String? = null
    override fun foregroundOutgoing(remoteName: String, video: Boolean) { foreground += "outgoing" + kind(video) }
    override fun foregroundIncoming(remoteName: String, video: Boolean) { foreground += "incoming" + kind(video) }
    override fun showIncoming(remoteName: String, video: Boolean) { foreground += "show-incoming" + kind(video) }
    override fun foregroundOngoing(remoteName: String, video: Boolean, camera: Boolean): Boolean {
        val withCamera = camera && cameraTypeAllowed
        foreground += "ongoing" + kind(video) + if (withCamera) "+camera" else ""
        return withCamera || !camera
    }
    override fun showOngoing(remoteName: String, video: Boolean) { foreground += "show-ongoing" + kind(video) }
    override fun setForegroundCamera(camera: Boolean): Boolean {
        if (camera && !cameraTypeAllowed) return false
        foreground += if (camera) "camera-type:on" else "camera-type:off"
        return true
    }
    override fun createLocalMedia(listener: CallLocalMedia.Listener) = FakeCallLocalMedia(listener).also { media += it }
    override fun startAudioSession() { audioSessionStarts++ }
    override fun setAudioFollowsVideo(preferSpeaker: Boolean, videoShowing: Boolean) {
        this.preferSpeaker = preferSpeaker
        this.videoShowing = videoShowing
    }
    override fun stopAudioSession() { audioSessionStops++ }
    override fun onSessionFinished(session: CallSession) { finished += session }
}

/**
 * Records what the session asks of the call's media. The camera answers nothing by itself: a test
 * calls the [listener], as the camera does later on its own thread. It does not drop callbacks
 * after [dispose]: the session must ignore them itself.
 */
internal class FakeCallLocalMedia(val listener: CallLocalMedia.Listener) : CallLocalMedia {

    /** One connection the session opened, and the events a test makes it report. */
    class Peer(
        val remoteId: String,
        val signaling: PeerSignaling,
        val offers: Boolean,
        val offerVideoLine: Boolean,
        val iceServers: List<IceServerData>,
    ) {
        // A channel, as in PeerSession: an event reported before the session collects is kept.
        val events = Channel<PeerSessionEvent>(Channel.UNLIMITED)

        fun emit(event: PeerSessionEvent) {
            events.trySend(event)
        }
    }

    val peers = mutableListOf<Peer>()
    var microphoneEnabled: Boolean? = null

    /** Every start and stop asked of the camera, in order. */
    val cameraRequests = mutableListOf<Boolean>()
    var flips = 0
    val framedIds = MutableStateFlow<Set<String>>(emptySet())
    val awaitedFrames = mutableListOf<String>()
    var disposed = false

    override val framed: Flow<Set<String>> get() = framedIds

    override fun openPeer(
        remoteId: String,
        signaling: PeerSignaling,
        offers: Boolean,
        offerVideoLine: Boolean,
        iceServers: List<IceServerData>,
    ): Flow<PeerSessionEvent> =
        Peer(remoteId, signaling, offers, offerVideoLine, iceServers).also { peers += it }.events.receiveAsFlow()

    override fun setMicrophoneEnabled(enabled: Boolean) {
        microphoneEnabled = enabled
    }

    override fun setCameraRunning(running: Boolean) {
        cameraRequests += running
    }

    override fun flipCamera() {
        flips++
    }

    override fun awaitFrame(participantId: String) {
        awaitedFrames += participantId
    }

    override fun dispose() {
        disposed = true
    }
}
