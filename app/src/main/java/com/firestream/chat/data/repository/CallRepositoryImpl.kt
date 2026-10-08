// region: AGENT-NOTE
// Responsibility: WebRTC call signalling — create/end calls, exchange SDP +
//   ICE candidates via Firestore. Also writes a CALL message into the chat
//   so the call shows up in CallsScreen's call log.
// Owns: Coordination between FirestoreCallSource (signalling docs) and the
//   message stream (call-log entries). Stateless — call state itself lives in
//   CallStateHolder + CallService, not here. Every call lets cancellation
//   through (cancellableResultOf): a cancelled caller stops instead of
//   carrying on with a failure.
// Collaborators: CallSignalingSource, FirestoreMessageSource, ChatDao, CallService.
// Don't put here: PeerConnection lifecycle (WebRtcCallMedia), in-call UI state
//   (CallStateHolder), call-log derivation (CallsViewModel).
// endregion

package com.firestream.chat.data.repository

import com.firestream.chat.data.local.dao.ChatDao
import com.firestream.chat.data.outbox.SendClock
import com.firestream.chat.data.remote.source.AuthSource
import com.firestream.chat.data.remote.source.CallSignalingSource
import com.firestream.chat.data.remote.source.MessageSource
import com.firestream.chat.data.util.cancellableResultOf
import com.firestream.chat.domain.model.CallSignalingData
import com.firestream.chat.domain.model.IceCandidateData
import com.firestream.chat.domain.model.MessageType
import com.firestream.chat.domain.model.SdpData
import com.firestream.chat.domain.repository.CallRepository
import kotlinx.coroutines.flow.Flow
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class CallRepositoryImpl @Inject constructor(
    private val callSource: CallSignalingSource,
    private val authSource: AuthSource,
    private val messageSource: MessageSource,
    private val chatDao: ChatDao,
    private val sendClock: SendClock,
) : CallRepository {

    override suspend fun createCall(calleeId: String): Result<String> = cancellableResultOf {
        val callerId = authSource.currentUserId
            ?: return Result.failure(Exception("Not authenticated"))
        callSource.createCallDocument(callerId, calleeId)
    }

    override suspend fun answerCall(callId: String): Result<Unit> = cancellableResultOf {
        callSource.updateCallStatus(callId, "answered")
    }

    override suspend fun declineCall(callId: String): Result<Unit> = cancellableResultOf {
        callSource.updateCallStatus(callId, "declined", "declined")
    }

    override suspend fun endCall(callId: String, reason: String): Result<Unit> = cancellableResultOf {
        callSource.updateCallStatus(callId, "ended", reason)
    }

    override suspend fun sendOffer(callId: String, sdp: SdpData): Result<Unit> = cancellableResultOf {
        callSource.setOffer(callId, sdp)
    }

    override suspend fun sendAnswer(callId: String, sdp: SdpData): Result<Unit> = cancellableResultOf {
        callSource.setAnswer(callId, sdp)
    }

    override suspend fun sendAnswerAndAccept(callId: String, sdp: SdpData): Result<Unit> = cancellableResultOf {
        callSource.setAnswerAndAccept(callId, sdp)
    }

    override suspend fun sendIceCandidate(
        callId: String,
        isCaller: Boolean,
        candidate: IceCandidateData
    ): Result<Unit> = cancellableResultOf {
        val subcollection = if (isCaller) "callerCandidates" else "calleeCandidates"
        callSource.addIceCandidate(callId, subcollection, candidate)
    }

    override fun observeCallDocument(callId: String): Flow<CallSignalingData> {
        return callSource.observeCallDocument(callId)
    }

    override fun observeIceCandidates(callId: String, subcollection: String): Flow<List<IceCandidateData>> {
        return callSource.observeIceCandidates(callId, subcollection)
    }

    override suspend fun getCallById(callId: String): Result<CallSignalingData> = cancellableResultOf {
        callSource.getCallById(callId) ?: return Result.failure(Exception("Call not found"))
    }

    override suspend fun logCallMessage(chatId: String, endReason: String, durationSeconds: Int): Result<Unit> =
        cancellableResultOf {
            val callerId = authSource.currentUserId
                ?: return Result.failure(Exception("Not authenticated"))
            val timestamp = sendClock.next()
            val remoteId = messageSource.sendCallMessage(chatId, callerId, endReason, durationSeconds, timestamp)
            chatDao.updateLastMessage(chatId, remoteId, messageSource.lastContentFor(MessageType.CALL), timestamp)
        }
}
