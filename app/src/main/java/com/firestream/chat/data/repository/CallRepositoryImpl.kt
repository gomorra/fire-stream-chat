package com.firestream.chat.data.repository

import com.firestream.chat.data.local.dao.ChatDao
import com.firestream.chat.data.remote.firebase.FirebaseAuthSource
import com.firestream.chat.data.remote.firebase.FirestoreCallSource
import com.firestream.chat.data.remote.firebase.FirestoreMessageSource
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
    private val callSource: FirestoreCallSource,
    private val authSource: FirebaseAuthSource,
    private val messageSource: FirestoreMessageSource,
    private val chatDao: ChatDao
) : CallRepository {

    override suspend fun createCall(calleeId: String): Result<String> {
        return try {
            val callerId = authSource.currentUserId
                ?: return Result.failure(Exception("Not authenticated"))
            val callId = callSource.createCallDocument(callerId, calleeId)
            Result.success(callId)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    override suspend fun answerCall(callId: String): Result<Unit> {
        return try {
            callSource.updateCallStatus(callId, "answered")
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    override suspend fun declineCall(callId: String): Result<Unit> {
        return try {
            callSource.updateCallStatus(callId, "declined", "declined")
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    override suspend fun endCall(callId: String, reason: String): Result<Unit> {
        return try {
            callSource.updateCallStatus(callId, "ended", reason)
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    override suspend fun sendOffer(callId: String, sdp: SdpData): Result<Unit> {
        return try {
            callSource.setOffer(callId, sdp)
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    override suspend fun sendAnswer(callId: String, sdp: SdpData): Result<Unit> {
        return try {
            callSource.setAnswer(callId, sdp)
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    override suspend fun sendAnswerAndAccept(callId: String, sdp: SdpData): Result<Unit> {
        return try {
            callSource.setAnswerAndAccept(callId, sdp)
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    override suspend fun sendIceCandidate(
        callId: String,
        isCaller: Boolean,
        candidate: IceCandidateData
    ): Result<Unit> {
        return try {
            val subcollection = if (isCaller) "callerCandidates" else "calleeCandidates"
            callSource.addIceCandidate(callId, subcollection, candidate)
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    override fun observeCallDocument(callId: String): Flow<CallSignalingData> {
        return callSource.observeCallDocument(callId)
    }

    override fun observeIceCandidates(callId: String, subcollection: String): Flow<List<IceCandidateData>> {
        return callSource.observeIceCandidates(callId, subcollection)
    }

    override suspend fun getCallById(callId: String): Result<CallSignalingData> {
        return try {
            val data = callSource.getCallById(callId)
                ?: return Result.failure(Exception("Call not found"))
            Result.success(data)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    override suspend fun logCallMessage(chatId: String, endReason: String, durationSeconds: Int): Result<Unit> {
        return try {
            val callerId = authSource.currentUserId
                ?: return Result.failure(Exception("Not authenticated"))
            val timestamp = System.currentTimeMillis()
            val remoteId = messageSource.sendCallMessage(chatId, callerId, endReason, durationSeconds, timestamp)
            chatDao.updateLastMessage(chatId, remoteId, messageSource.lastContentFor(MessageType.CALL), timestamp)
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
}
