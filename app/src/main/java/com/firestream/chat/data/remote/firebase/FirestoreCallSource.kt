package com.firestream.chat.data.remote.firebase

import com.firestream.chat.domain.model.CallSignalingData
import com.firestream.chat.domain.model.IceCandidateData
import com.firestream.chat.domain.model.SdpData
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.ListenerRegistration
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.tasks.await
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class FirestoreCallSource @Inject constructor(
    private val firestore: FirebaseFirestore
) {
    private val callsCollection get() = firestore.collection("calls")

    suspend fun createCallDocument(callerId: String, calleeId: String): String {
        val callId = callsCollection.document().id
        val data = hashMapOf(
            "callerId" to callerId,
            "calleeId" to calleeId,
            "status" to "ringing",
            "createdAt" to System.currentTimeMillis(),
            "endedAt" to null,
            "endReason" to null,
            "offer" to null,
            "answer" to null
        )
        callsCollection.document(callId).set(data).await()
        return callId
    }

    suspend fun updateCallStatus(callId: String, status: String, endReason: String? = null) {
        val updates = hashMapOf<String, Any?>(
            "status" to status
        )
        if (endReason != null) {
            updates["endReason"] = endReason
            updates["endedAt"] = System.currentTimeMillis()
        }
        callsCollection.document(callId).update(updates).await()
    }

    suspend fun setOffer(callId: String, sdp: SdpData) {
        callsCollection.document(callId).update(
            "offer", hashMapOf("sdp" to sdp.sdp, "type" to sdp.type)
        ).await()
    }

    suspend fun setAnswer(callId: String, sdp: SdpData) {
        callsCollection.document(callId).update(
            "answer", hashMapOf("sdp" to sdp.sdp, "type" to sdp.type)
        ).await()
    }

    suspend fun addIceCandidate(callId: String, subcollection: String, candidate: IceCandidateData) {
        val data = hashMapOf(
            "sdpMid" to candidate.sdpMid,
            "sdpMLineIndex" to candidate.sdpMLineIndex,
            "sdp" to candidate.sdp
        )
        callsCollection.document(callId)
            .collection(subcollection)
            .add(data)
            .await()
    }

    fun observeCallDocument(callId: String): Flow<CallSignalingData> = callbackFlow {
        val listener: ListenerRegistration = callsCollection.document(callId)
            .addSnapshotListener { snapshot, error ->
                if (error != null) {
                    close(error)
                    return@addSnapshotListener
                }
                val data = snapshot?.data ?: return@addSnapshotListener
                trySend(mapToCallSignaling(callId, data))
            }
        awaitClose { listener.remove() }
    }

    fun observeIceCandidates(callId: String, subcollection: String): Flow<List<IceCandidateData>> = callbackFlow {
        val listener: ListenerRegistration = callsCollection.document(callId)
            .collection(subcollection)
            .addSnapshotListener { snapshot, error ->
                if (error != null) {
                    close(error)
                    return@addSnapshotListener
                }
                val candidates = snapshot?.documents?.mapNotNull { doc ->
                    val d = doc.data ?: return@mapNotNull null
                    IceCandidateData(
                        sdpMid = d["sdpMid"] as? String ?: return@mapNotNull null,
                        sdpMLineIndex = (d["sdpMLineIndex"] as? Number)?.toInt() ?: return@mapNotNull null,
                        sdp = d["sdp"] as? String ?: return@mapNotNull null
                    )
                } ?: emptyList()
                trySend(candidates)
            }
        awaitClose { listener.remove() }
    }

    suspend fun getCallById(callId: String): CallSignalingData? {
        val snapshot = callsCollection.document(callId).get().await()
        val data = snapshot.data ?: return null
        return mapToCallSignaling(callId, data)
    }

    private fun mapToCallSignaling(callId: String, data: Map<String, Any?>): CallSignalingData {
        val offerMap = data["offer"] as? Map<*, *>
        val answerMap = data["answer"] as? Map<*, *>
        return CallSignalingData(
            callId = callId,
            callerId = data["callerId"] as? String ?: "",
            calleeId = data["calleeId"] as? String ?: "",
            status = data["status"] as? String ?: "ended",
            offer = offerMap?.let { SdpData(it["sdp"] as? String ?: "", it["type"] as? String ?: "") },
            answer = answerMap?.let { SdpData(it["sdp"] as? String ?: "", it["type"] as? String ?: "") },
            createdAt = (data["createdAt"] as? Number)?.toLong() ?: 0L,
            endedAt = (data["endedAt"] as? Number)?.toLong(),
            endReason = data["endReason"] as? String
        )
    }
}
