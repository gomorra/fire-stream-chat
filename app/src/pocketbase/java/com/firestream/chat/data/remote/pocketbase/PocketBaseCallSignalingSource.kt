package com.firestream.chat.data.remote.pocketbase

import com.firestream.chat.data.remote.source.CallSignalingSource
import com.firestream.chat.domain.model.CallSignalingData
import com.firestream.chat.domain.model.IceCandidateData
import com.firestream.chat.domain.model.SdpData
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Calls are out of scope for the PocketBase backend, and the app offers none
 * (`BuildConfig.SUPPORTS_CALLS` is false). Each call throws
 * [UnsupportedOperationException], an [Exception], so `CallRepositoryImpl` turns it into a
 * failure. `NotImplementedError` is an `Error`, which that catch lets through.
 */
@Singleton
class PocketBaseCallSignalingSource @Inject constructor() : CallSignalingSource {
    override suspend fun createCallDocument(callerId: String, calleeId: String, video: Boolean): String =
        throw unsupported()

    override suspend fun updateCallStatus(callId: String, status: String, endReason: String?): Unit =
        throw unsupported()

    override suspend fun setOffer(callId: String, sdp: SdpData): Unit =
        throw unsupported()

    override suspend fun setAnswer(callId: String, sdp: SdpData): Unit =
        throw unsupported()

    override suspend fun setAnswerAndAccept(callId: String, sdp: SdpData): Unit =
        throw unsupported()

    override suspend fun addIceCandidate(
        callId: String,
        subcollection: String,
        candidate: IceCandidateData
    ): Unit = throw unsupported()

    override suspend fun setMedia(callId: String, uid: String, camera: Boolean, mic: Boolean): Unit =
        throw unsupported()

    override fun observeCallDocument(callId: String): Flow<CallSignalingData> = emptyFlow()

    override fun observeIceCandidates(callId: String, subcollection: String): Flow<List<IceCandidateData>> =
        emptyFlow()

    override suspend fun getCallById(callId: String): CallSignalingData? =
        throw unsupported()

    private fun unsupported() = UnsupportedOperationException("Calls are not available on the PocketBase backend")
}
