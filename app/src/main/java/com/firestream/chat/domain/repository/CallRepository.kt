package com.firestream.chat.domain.repository

import com.firestream.chat.domain.model.CallSignalingData
import com.firestream.chat.domain.model.EndReason
import com.firestream.chat.domain.model.IceCandidateData
import com.firestream.chat.domain.model.OutgoingCall
import com.firestream.chat.domain.model.SdpData
import kotlinx.coroutines.flow.Flow

interface CallRepository {
    /**
     * A call is about to start or to be answered. Fetches ahead what its connection needs, the
     * relay's servers, and returns at once. Safe to call any number of times.
     */
    fun prepareCall()

    /**
     * Ring [calleeId]. Before the call document exists it asks whether the callee's app takes a
     * video line and fetches the relay's servers, and waits a bounded time for both. The caller
     * builds its offer from [OutgoingCall.videoLine].
     */
    suspend fun createCall(calleeId: String, video: Boolean): Result<OutgoingCall>
    suspend fun answerCall(callId: String): Result<Unit>
    suspend fun declineCall(callId: String): Result<Unit>
    suspend fun endCall(callId: String, reason: EndReason): Result<Unit>
    suspend fun sendOffer(callId: String, sdp: SdpData): Result<Unit>
    suspend fun sendAnswer(callId: String, sdp: SdpData): Result<Unit>
    suspend fun sendAnswerAndAccept(callId: String, sdp: SdpData): Result<Unit>
    suspend fun sendIceCandidate(callId: String, isCaller: Boolean, candidate: IceCandidateData): Result<Unit>

    /** Publish the signed-in user's own live state: whether the camera sends and the microphone is open. */
    suspend fun setMedia(callId: String, camera: Boolean, mic: Boolean): Result<Unit>
    fun observeCallDocument(callId: String): Flow<CallSignalingData>
    fun observeIceCandidates(callId: String, subcollection: String): Flow<List<IceCandidateData>>
    suspend fun getCallById(callId: String): Result<CallSignalingData>
    suspend fun logCallMessage(chatId: String, endReason: EndReason, durationSeconds: Int, video: Boolean): Result<Unit>
}
