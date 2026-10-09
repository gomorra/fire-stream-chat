// region: AGENT-NOTE
// Responsibility: WebRTC signalling I/O — `calls/{callId}` doc + `callerCandidates`
//   / `calleeCandidates` ICE subcollections. Status transitions
//   (ringing → answered → ended), SDP offer/answer storage, ICE candidate streams,
//   and each side's live camera and microphone state (`media.<uid>`).
// Owns: Listener registrations on `calls/*` for call status + ICE candidates.
// Collaborators: CallRepositoryImpl (only caller); the Cloud Function
//   `sendCallPushNotification` triggers off `calls/*` document creates with
//   status == ringing.
// Don't put here: PeerConnection itself (PeerSession), call-log derivation
//   (call-type messages live in FirestoreMessageSource).
// endregion

package com.firestream.chat.data.remote.firebase

import com.firestream.chat.data.remote.source.CallSignalingSource
import com.firestream.chat.domain.model.CallMedia
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
) : CallSignalingSource {
    private val callsCollection get() = firestore.collection("calls")

    /**
     * Create the call in a transaction, not with set(). A set() made offline waits in the local
     * cache and commits whenever the phone is next online, so the callee's phone would ring for a
     * call nobody is making any more. A transaction needs the server, and fails instead.
     *
     * The transaction only writes: the rules refuse to read a call that does not exist yet.
     */
    override suspend fun createCallDocument(callerId: String, calleeId: String, video: Boolean): String {
        val callRef = callsCollection.document()
        val data = hashMapOf(
            "callerId" to callerId,
            "calleeId" to calleeId,
            "status" to "ringing",
            "video" to video,
            "createdAt" to System.currentTimeMillis(),
            "endedAt" to null,
            "endReason" to null,
            "offer" to null,
            "answer" to null
        )
        firestore.runTransaction { transaction -> transaction.set(callRef, data) }.await()
        return callRef.id
    }

    override suspend fun updateCallStatus(callId: String, status: String, endReason: String?) {
        val updates = hashMapOf<String, Any?>(
            "status" to status
        )
        if (endReason != null) {
            updates["endReason"] = endReason
            updates["endedAt"] = System.currentTimeMillis()
        }
        callsCollection.document(callId).update(updates).await()
    }

    override suspend fun setOffer(callId: String, sdp: SdpData) {
        callsCollection.document(callId).update(
            "offer", hashMapOf("sdp" to sdp.sdp, "type" to sdp.type)
        ).await()
    }

    override suspend fun setAnswer(callId: String, sdp: SdpData) {
        callsCollection.document(callId).update(
            "answer", hashMapOf("sdp" to sdp.sdp, "type" to sdp.type)
        ).await()
    }

    /** Write answer SDP and status="answered" atomically so the caller always sees both. */
    override suspend fun setAnswerAndAccept(callId: String, sdp: SdpData) {
        callsCollection.document(callId).update(
            mapOf(
                "answer" to hashMapOf("sdp" to sdp.sdp, "type" to sdp.type),
                "status" to "answered"
            )
        ).await()
    }

    override suspend fun addIceCandidate(callId: String, subcollection: String, candidate: IceCandidateData) {
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

    /** `media.<uid>` on the call document. The dotted paths leave the other side's entry alone. */
    override suspend fun setMedia(callId: String, uid: String, camera: Boolean, mic: Boolean) {
        callsCollection.document(callId).update(
            mapOf(
                "media.$uid.camera" to camera,
                "media.$uid.mic" to mic
            )
        ).await()
    }

    override fun observeCallDocument(callId: String): Flow<CallSignalingData> = callbackFlow {
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

    override fun observeIceCandidates(callId: String, subcollection: String): Flow<List<IceCandidateData>> = callbackFlow {
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

    override suspend fun getCallById(callId: String): CallSignalingData? {
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
            endReason = data["endReason"] as? String,
            // An older app writes no kind: its calls are voice calls.
            video = data["video"] as? Boolean ?: false,
            media = mapToMedia(data["media"] as? Map<*, *>)
        )
    }

    /** An entry that is not a map is skipped. A missing field reads as camera off, microphone on. */
    private fun mapToMedia(media: Map<*, *>?): Map<String, CallMedia> {
        if (media == null) return emptyMap()
        val unsaid = CallMedia()
        return buildMap {
            media.forEach { (uid, state) ->
                if (uid is String && state is Map<*, *>) {
                    put(
                        uid,
                        CallMedia(
                            camera = state["camera"] as? Boolean ?: unsaid.camera,
                            mic = state["mic"] as? Boolean ?: unsaid.mic
                        )
                    )
                }
            }
        }
    }
}
