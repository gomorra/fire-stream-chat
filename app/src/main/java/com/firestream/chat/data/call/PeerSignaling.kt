package com.firestream.chat.data.call

import com.firestream.chat.domain.model.IceCandidateData
import com.firestream.chat.domain.model.SdpData
import com.firestream.chat.domain.repository.CallRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.mapNotNull

/**
 * What a [PeerSession] needs to negotiate with one remote person: send and observe the offer, the
 * answer and the ICE candidates of that pair. An implementation knows where they are stored; the
 * session does not.
 *
 * The `send*` functions do not throw. A write that fails is the implementation's to log, and the
 * session finds out when the connection never comes up.
 */
interface PeerSignaling {
    suspend fun sendOffer(sdp: SdpData)
    suspend fun sendAnswer(sdp: SdpData)

    /** Publish one of this side's ICE candidates. */
    suspend fun sendCandidate(candidate: IceCandidateData)

    /**
     * The remote side's offer. The session takes the first one. A flow that fails or ends empty
     * fails the session.
     */
    fun observeOffer(): Flow<SdpData>

    /** The remote side's answer. The session applies the first one. */
    fun observeAnswer(): Flow<SdpData>

    /**
     * The remote side's ICE candidates. An emission may repeat candidates of an earlier one, since
     * a snapshot listener delivers the whole list each time. The session filters duplicates.
     */
    fun observeCandidates(): Flow<List<IceCandidateData>>
}

/**
 * [PeerSignaling] for a 1:1 call, over the call document `calls/{callId}`. The caller's candidates
 * live in `callerCandidates` and the callee's in `calleeCandidates`, so each side writes its own
 * subcollection and observes the other.
 *
 * The answer is written together with `status = "answered"`, so the caller never sees the status
 * without the SDP.
 *
 * @param offer the offer the side that answers found in the call document. [CallSession] reads
 *   the document once before it answers, for the call's status, and passes the offer on. Null on
 *   the caller's side, which makes the offer.
 */
class OneToOneSignaling(
    private val callRepository: CallRepository,
    private val callId: String,
    private val isCaller: Boolean,
    private val offer: SdpData? = null
) : PeerSignaling {

    override suspend fun sendOffer(sdp: SdpData) {
        callRepository.sendOffer(callId, sdp)
    }

    override suspend fun sendAnswer(sdp: SdpData) {
        callRepository.sendAnswerAndAccept(callId, sdp)
    }

    override suspend fun sendCandidate(candidate: IceCandidateData) {
        callRepository.sendIceCandidate(callId, isCaller, candidate)
    }

    /** The one offer of the call: the callee answers after it was written. */
    override fun observeOffer(): Flow<SdpData> = flow {
        emit(offer ?: throw IllegalStateException("No offer for call $callId"))
    }

    override fun observeAnswer(): Flow<SdpData> =
        callRepository.observeCallDocument(callId)
            .filter { it.status == STATUS_ANSWERED }
            .mapNotNull { it.answer }
            .distinctUntilChanged()

    override fun observeCandidates(): Flow<List<IceCandidateData>> =
        callRepository.observeIceCandidates(
            callId,
            if (isCaller) CALLEE_CANDIDATES else CALLER_CANDIDATES
        )

    private companion object {
        const val STATUS_ANSWERED = "answered"
        const val CALLER_CANDIDATES = "callerCandidates"
        const val CALLEE_CANDIDATES = "calleeCandidates"
    }
}
