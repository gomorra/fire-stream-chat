package com.firestream.chat.data.remote.source

import com.firestream.chat.domain.model.CallSignalingData
import com.firestream.chat.domain.model.IceCandidateData
import com.firestream.chat.domain.model.SdpData
import kotlinx.coroutines.flow.Flow

/**
 * Backend-neutral WebRTC signalling boundary — call lifecycle, SDP offer/answer,
 * ICE candidate streams. Stub on the pocketbase flavor in v0.
 */
interface CallSignalingSource {
    suspend fun createCallDocument(callerId: String, calleeId: String): String
    suspend fun updateCallStatus(callId: String, status: String, endReason: String? = null)
    suspend fun setOffer(callId: String, sdp: SdpData)
    suspend fun setAnswer(callId: String, sdp: SdpData)
    suspend fun setAnswerAndAccept(callId: String, sdp: SdpData)
    suspend fun addIceCandidate(callId: String, subcollection: String, candidate: IceCandidateData)

    fun observeCallDocument(callId: String): Flow<CallSignalingData>
    fun observeIceCandidates(callId: String, subcollection: String): Flow<List<IceCandidateData>>

    suspend fun getCallById(callId: String): CallSignalingData?
}
