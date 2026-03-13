package com.firestream.chat.domain.repository

import com.firestream.chat.domain.model.CallSignalingData
import com.firestream.chat.domain.model.IceCandidateData
import com.firestream.chat.domain.model.SdpData
import kotlinx.coroutines.flow.Flow

interface CallRepository {
    suspend fun createCall(calleeId: String): Result<String>
    suspend fun answerCall(callId: String): Result<Unit>
    suspend fun declineCall(callId: String): Result<Unit>
    suspend fun endCall(callId: String, reason: String): Result<Unit>
    suspend fun sendOffer(callId: String, sdp: SdpData): Result<Unit>
    suspend fun sendAnswer(callId: String, sdp: SdpData): Result<Unit>
    suspend fun sendIceCandidate(callId: String, isCaller: Boolean, candidate: IceCandidateData): Result<Unit>
    fun observeCallDocument(callId: String): Flow<CallSignalingData>
    fun observeIceCandidates(callId: String, subcollection: String): Flow<List<IceCandidateData>>
    suspend fun getCallById(callId: String): Result<CallSignalingData>
    suspend fun logCallMessage(chatId: String, endReason: String, durationSeconds: Int): Result<Unit>
}
