package com.firestream.chat.data.remote.pocketbase

import com.firestream.chat.data.remote.source.CallSignalingSource
import com.firestream.chat.domain.model.CallSignalingData
import com.firestream.chat.domain.model.IceCandidateData
import com.firestream.chat.domain.model.SdpData
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import javax.inject.Inject
import javax.inject.Singleton

/** Step 4 stub. Stays a stub through v0; calls are out of scope. */
@Singleton
class PocketBaseCallSignalingSource @Inject constructor() : CallSignalingSource {
    override suspend fun createCallDocument(callerId: String, calleeId: String): String =
        throw NotImplementedError("PB v0 stub")

    override suspend fun updateCallStatus(callId: String, status: String, endReason: String?): Unit =
        throw NotImplementedError("PB v0 stub")

    override suspend fun setOffer(callId: String, sdp: SdpData): Unit =
        throw NotImplementedError("PB v0 stub")

    override suspend fun setAnswer(callId: String, sdp: SdpData): Unit =
        throw NotImplementedError("PB v0 stub")

    override suspend fun setAnswerAndAccept(callId: String, sdp: SdpData): Unit =
        throw NotImplementedError("PB v0 stub")

    override suspend fun addIceCandidate(
        callId: String,
        subcollection: String,
        candidate: IceCandidateData
    ): Unit = throw NotImplementedError("PB v0 stub")

    override fun observeCallDocument(callId: String): Flow<CallSignalingData> = emptyFlow()

    override fun observeIceCandidates(callId: String, subcollection: String): Flow<List<IceCandidateData>> =
        emptyFlow()

    override suspend fun getCallById(callId: String): CallSignalingData? =
        throw NotImplementedError("PB v0 stub")
}
