package com.firestream.chat.data.call

import com.firestream.chat.domain.model.IceCandidateData
import com.firestream.chat.domain.model.SdpData
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.webrtc.IceCandidate
import org.webrtc.MediaStreamTrack
import org.webrtc.PeerConnection
import org.webrtc.PeerConnection.IceConnectionState
import org.webrtc.RtpTransceiver
import org.webrtc.SdpObserver
import org.webrtc.SessionDescription

/**
 * Plain JUnit, no Robolectric and no native library: `PeerConnection` and the tracks are MockK'd,
 * and the test plays the WebRTC signalling thread by calling the captured observers itself. The
 * scope runs on an unconfined dispatcher, so every launch inside the session has finished by the
 * time the call that caused it returns.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class PeerSessionTest {

    private val pc: PeerConnection = mockk(relaxed = true)
    private val factory: WebRtcPeerConnectionFactory = mockk()
    private val localTrack: MediaStreamTrack = mockk(relaxed = true)
    private val signaling = FakePeerSignaling()
    private val scope = CoroutineScope(UnconfinedTestDispatcher() + SupervisorJob())

    private val connectionObserver = slot<PeerConnection.Observer>()
    private val createObservers = mutableListOf<SdpObserver>()
    private val localDescriptions = mutableListOf<SessionDescription>()
    private val localObservers = mutableListOf<SdpObserver>()
    private val remoteDescriptions = mutableListOf<SessionDescription>()
    private val remoteObservers = mutableListOf<SdpObserver>()
    private val events = mutableListOf<PeerSessionEvent>()
    private lateinit var eventsJob: Job

    private val offer = SdpData("offer-sdp", "offer")
    private val answer = SdpData("answer-sdp", "answer")
    private val first = IceCandidateData("0", 0, "candidate:1 1 udp 1 10.0.0.1 1000 typ host")
    private val second = IceCandidateData("0", 0, "candidate:2 1 udp 1 1.2.3.4 2000 typ srflx")

    @Before
    fun setUp() {
        every { factory.createPeerConnection(capture(connectionObserver)) } returns pc
        every { pc.createOffer(capture(createObservers), any()) } returns Unit
        every { pc.createAnswer(capture(createObservers), any()) } returns Unit
        every { pc.setLocalDescription(capture(localObservers), capture(localDescriptions)) } returns Unit
        every { pc.setRemoteDescription(capture(remoteObservers), capture(remoteDescriptions)) } returns Unit
        every { pc.addIceCandidate(any<IceCandidate>()) } returns true
    }

    @After
    fun tearDown() {
        scope.cancel()
    }

    private fun session(offers: Boolean): PeerSession {
        val session = PeerSession(factory, signaling, listOf(localTrack), offers, scope)
        eventsJob = scope.launch { session.events.toList(events) }
        return session
    }

    /** An offering session whose answer has been applied, so candidates go straight through. */
    private fun negotiatedOfferer(): PeerSession {
        val session = session(offers = true)
        session.start()
        signaling.answers.tryEmit(answer)
        remoteObservers.single().onSetSuccess()
        return session
    }

    private fun IceCandidateData.toIce() = IceCandidate(sdpMid, sdpMLineIndex, sdp)

    // ── Offer flow ───────────────────────────────────────────────────────────

    @Test
    fun `an offering session adds the local track, creates an offer and sends it`() {
        session(offers = true).start()

        verify { pc.addTrack(localTrack) }
        createObservers.single().onCreateSuccess(SessionDescription(SessionDescription.Type.OFFER, "offer-sdp"))

        assertEquals("offer-sdp", localDescriptions.single().description)
        assertEquals(listOf(offer), signaling.sentOffers)
        verify(exactly = 0) { pc.createAnswer(any(), any()) }
    }

    @Test
    fun `an offering session applies the remote answer, and only the first one`() {
        session(offers = true).start()

        signaling.answers.tryEmit(answer)
        signaling.answers.tryEmit(SdpData("another", "answer"))

        val applied = remoteDescriptions.single()
        assertEquals(SessionDescription.Type.ANSWER, applied.type)
        assertEquals("answer-sdp", applied.description)
    }

    // ── Answer flow ──────────────────────────────────────────────────────────

    @Test
    fun `an answering session applies the offer, then creates an answer and sends it`() {
        signaling.offer = flowOf(offer)
        session(offers = false).start()

        assertEquals(SessionDescription.Type.OFFER, remoteDescriptions.single().type)
        // The answer waits for the remote description.
        assertTrue(createObservers.isEmpty())

        remoteObservers.single().onSetSuccess()
        createObservers.single().onCreateSuccess(SessionDescription(SessionDescription.Type.ANSWER, "answer-sdp"))

        assertEquals("answer-sdp", localDescriptions.single().description)
        assertEquals(listOf(answer), signaling.sentAnswers)
        verify(exactly = 0) { pc.createOffer(any(), any()) }
    }

    // ── ICE candidates ───────────────────────────────────────────────────────

    @Test
    fun `candidates that arrive before the remote description are held, then applied`() {
        session(offers = true).start()

        signaling.candidates.tryEmit(listOf(first, second))
        verify(exactly = 0) { pc.addIceCandidate(any<IceCandidate>()) }

        signaling.answers.tryEmit(answer)
        verify(exactly = 0) { pc.addIceCandidate(any<IceCandidate>()) }

        remoteObservers.single().onSetSuccess()
        verify(exactly = 1) { pc.addIceCandidate(first.toIce()) }
        verify(exactly = 1) { pc.addIceCandidate(second.toIce()) }
    }

    @Test
    fun `a candidate held early is not applied again when a later snapshot repeats it`() {
        session(offers = true).start()
        signaling.candidates.tryEmit(listOf(first))
        signaling.answers.tryEmit(answer)
        remoteObservers.single().onSetSuccess()

        signaling.candidates.tryEmit(listOf(first, second))

        verify(exactly = 1) { pc.addIceCandidate(first.toIce()) }
        verify(exactly = 1) { pc.addIceCandidate(second.toIce()) }
    }

    @Test
    fun `duplicate candidates are dropped`() {
        negotiatedOfferer()

        signaling.candidates.tryEmit(listOf(first))
        signaling.candidates.tryEmit(listOf(first, second))
        signaling.candidates.tryEmit(listOf(first, second))

        verify(exactly = 1) { pc.addIceCandidate(first.toIce()) }
        verify(exactly = 1) { pc.addIceCandidate(second.toIce()) }
    }

    @Test
    fun `a local candidate is sent through signaling`() {
        session(offers = true).start()

        connectionObserver.captured.onIceCandidate(first.toIce())

        assertEquals(listOf(first), signaling.sentCandidates)
    }

    // ── Events ───────────────────────────────────────────────────────────────

    @Test
    fun `connected is reported once for CONNECTED and COMPLETED`() {
        session(offers = true).start()

        connectionObserver.captured.onIceConnectionChange(IceConnectionState.CONNECTED)
        connectionObserver.captured.onIceConnectionChange(IceConnectionState.COMPLETED)

        assertEquals(listOf<PeerSessionEvent>(PeerSessionEvent.Connected), events)
    }

    @Test
    fun `a session that drops and comes back reports disconnected, then connected again`() {
        session(offers = true).start()

        connectionObserver.captured.onIceConnectionChange(IceConnectionState.CONNECTED)
        connectionObserver.captured.onIceConnectionChange(IceConnectionState.DISCONNECTED)
        connectionObserver.captured.onIceConnectionChange(IceConnectionState.CONNECTED)

        assertEquals(
            listOf(PeerSessionEvent.Connected, PeerSessionEvent.Disconnected, PeerSessionEvent.Connected),
            events
        )
    }

    @Test
    fun `a remote track is reported`() {
        session(offers = true).start()
        val remoteTrack: MediaStreamTrack = mockk()
        val transceiver: RtpTransceiver = mockk { every { receiver.track() } returns remoteTrack }

        connectionObserver.captured.onTrack(transceiver)

        assertEquals(listOf<PeerSessionEvent>(PeerSessionEvent.RemoteTrack(remoteTrack)), events)
    }

    // ── Failures ─────────────────────────────────────────────────────────────

    @Test
    fun `ICE FAILED is a failure event`() {
        session(offers = true).start()

        connectionObserver.captured.onIceConnectionChange(IceConnectionState.FAILED)

        assertTrue(events.single() is PeerSessionEvent.Failed)
    }

    @Test
    fun `an offer that cannot be created is a failure event`() {
        session(offers = true).start()

        createObservers.single().onCreateFailure("boom")

        assertTrue(events.single() is PeerSessionEvent.Failed)
        assertTrue(signaling.sentOffers.isEmpty())
    }

    @Test
    fun `a local description that cannot be set is a failure event`() {
        session(offers = true).start()
        createObservers.single().onCreateSuccess(SessionDescription(SessionDescription.Type.OFFER, "offer-sdp"))

        localObservers.single().onSetFailure("boom")

        assertTrue(events.single() is PeerSessionEvent.Failed)
    }

    @Test
    fun `a remote description that cannot be set is a failure event, and no answer is created`() {
        signaling.offer = flowOf(offer)
        session(offers = false).start()

        remoteObservers.single().onSetFailure("boom")

        assertTrue(events.single() is PeerSessionEvent.Failed)
        assertTrue(createObservers.isEmpty())
    }

    @Test
    fun `a remote description of an unknown type is a failure event`() {
        signaling.offer = flowOf(SdpData("sdp", "nonsense"))
        session(offers = false).start()

        assertTrue(events.single() is PeerSessionEvent.Failed)
        assertTrue(remoteDescriptions.isEmpty())
    }

    @Test
    fun `an offer that cannot be fetched is a failure event`() {
        signaling.offer = flow { throw IllegalStateException("no call document") }
        session(offers = false).start()

        assertTrue(events.single() is PeerSessionEvent.Failed)
    }

    @Test
    fun `an offer flow that ends empty is a failure event`() {
        signaling.offer = emptyFlow()
        session(offers = false).start()

        assertTrue(events.single() is PeerSessionEvent.Failed)
    }

    @Test
    fun `a factory that returns no connection is a failure event`() {
        every { factory.createPeerConnection(any()) } returns null

        session(offers = true).start()

        assertTrue(events.single() is PeerSessionEvent.Failed)
    }

    @Test
    fun `failure is reported once`() {
        session(offers = true).start()

        createObservers.single().onCreateFailure("boom")
        connectionObserver.captured.onIceConnectionChange(IceConnectionState.FAILED)

        assertEquals(1, events.size)
    }

    @Test
    fun `a failure never closes the connection — these callbacks run on its own signalling thread`() {
        signaling.offer = flowOf(offer)
        session(offers = false).start()

        remoteObservers.single().onSetFailure("boom")
        connectionObserver.captured.onIceConnectionChange(IceConnectionState.FAILED)

        verify(exactly = 0) { pc.close() }
    }

    @Test
    fun `a failed offer never closes the connection`() {
        session(offers = true).start()

        createObservers.single().onCreateFailure("boom")

        verify(exactly = 0) { pc.close() }
    }

    @Test
    fun `a local track the connection rejects is a failure event, not a crash`() {
        every { pc.addTrack(localTrack) } throws IllegalStateException("MediaStreamTrack has been disposed.")

        session(offers = true).start()

        assertTrue(events.single() is PeerSessionEvent.Failed)
        verify(exactly = 0) { pc.createOffer(any(), any()) }
    }

    // ── Lifecycle ────────────────────────────────────────────────────────────

    @Test
    fun `a close that lands while the connection is being created still closes it, once`() {
        lateinit var session: PeerSession
        // close() from another thread, between start()'s first check and the connection existing.
        every { factory.createPeerConnection(any()) } answers {
            session.close()
            pc
        }
        session = session(offers = true)

        session.start()

        verify(exactly = 1) { pc.close() }
        verify(exactly = 0) { pc.addTrack(any()) }
        verify(exactly = 0) { pc.createOffer(any(), any()) }
        assertTrue(events.isEmpty())
    }

    @Test
    fun `start twice creates one connection`() {
        val session = session(offers = true)

        session.start()
        session.start()

        verify(exactly = 1) { factory.createPeerConnection(any()) }
        verify(exactly = 1) { pc.createOffer(any(), any()) }
    }

    @Test
    fun `close twice closes the connection once and completes the events`() {
        val session = session(offers = true)
        session.start()

        session.close()
        session.close()

        verify(exactly = 1) { pc.close() }
        assertTrue(eventsJob.isCompleted)
    }

    @Test
    fun `start after close creates no connection`() {
        val session = session(offers = true)

        session.close()
        session.start()

        verify(exactly = 0) { factory.createPeerConnection(any()) }
    }

    @Test
    fun `a closed session reports nothing, applies nothing and sends nothing`() {
        val session = negotiatedOfferer()
        session.close()

        connectionObserver.captured.onIceConnectionChange(IceConnectionState.FAILED)
        connectionObserver.captured.onIceCandidate(first.toIce())
        signaling.candidates.tryEmit(listOf(first))
        createObservers.single().onCreateSuccess(SessionDescription(SessionDescription.Type.OFFER, "offer-sdp"))

        assertTrue(events.isEmpty())
        assertTrue(signaling.sentCandidates.isEmpty())
        assertTrue(signaling.sentOffers.isEmpty())
        verify(exactly = 0) { pc.addIceCandidate(any<IceCandidate>()) }
        verify(exactly = 0) { pc.setLocalDescription(any(), any()) }
    }

    @Test
    fun `closing does not dispose the local track`() {
        val session = session(offers = true)
        session.start()

        session.close()

        verify(exactly = 0) { localTrack.dispose() }
    }
}

private class FakePeerSignaling : PeerSignaling {
    val sentOffers = mutableListOf<SdpData>()
    val sentAnswers = mutableListOf<SdpData>()
    val sentCandidates = mutableListOf<IceCandidateData>()

    var offer: Flow<SdpData> = emptyFlow()
    val answers = MutableSharedFlow<SdpData>(extraBufferCapacity = 16)
    val candidates = MutableSharedFlow<List<IceCandidateData>>(extraBufferCapacity = 16)

    override suspend fun sendOffer(sdp: SdpData) {
        sentOffers += sdp
    }

    override suspend fun sendAnswer(sdp: SdpData) {
        sentAnswers += sdp
    }

    override suspend fun sendCandidate(candidate: IceCandidateData) {
        sentCandidates += candidate
    }

    override fun observeOffer(): Flow<SdpData> = offer
    override fun observeAnswer(): Flow<SdpData> = answers
    override fun observeCandidates(): Flow<List<IceCandidateData>> = candidates
}
