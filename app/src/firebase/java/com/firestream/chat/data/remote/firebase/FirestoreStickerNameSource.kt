// region: AGENT-NOTE
// Responsibility: Firestore I/O for `stickerNames/{stickerId}` — one document
//   per sticker, a request for a name or the naming service's answer to it.
// Owns: the shape of a request (stickerNameRequest), the read of a document
//   into RemoteStickerName, and which Firestore error means "refused".
// Collaborators: StickerNameSync (request, fetch), via the StickerNameSource
//   interface in data/remote/source/. `firestore.rules` lets a signed-in user
//   get one document and create one as a request, and nothing else. The naming
//   service (tools/sticker-namer) answers with the Admin SDK.
// Don't put here: who is asked for (data/sticker/StickerNameSync), whether an
//   answer can be trusted (data/sticker/StickerNameAnswer), a query over the
//   collection — the rules refuse every list, and they must.
// endregion

package com.firestream.chat.data.remote.firebase

import com.firestream.chat.data.remote.source.RemoteStickerName
import com.firestream.chat.data.remote.source.StickerNameSource
import com.firestream.chat.data.remote.source.StickerNamingRefusedException
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.FirebaseFirestoreException
import com.google.firebase.firestore.Source
import kotlinx.coroutines.tasks.await
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class FirestoreStickerNameSource @Inject constructor(
    private val firestore: FirebaseFirestore,
) : StickerNameSource {

    private val names get() = firestore.collection(COLLECTION)

    override val isSupported: Boolean = true

    /**
     * A transaction, so the write happens only when there is no document. The
     * rules refuse a write over a request or an answer, and a second phone that
     * asks for the same sticker must not read that as "naming is refused".
     */
    override suspend fun request(stickerId: String, format: String): Unit = refusable {
        val ref = names.document(stickerId)
        firestore.runTransaction { tx ->
            if (!tx.get(ref).exists()) tx.set(ref, stickerNameRequest(format))
        }.await()
    }

    /**
     * Source.SERVER: a plain `get()` answers from the SDK's cache when the phone
     * is offline, and a cached request would read as "still not answered" for
     * as long as the cache holds it.
     */
    override suspend fun fetch(stickerId: String): RemoteStickerName? = refusable {
        names.document(stickerId).get(Source.SERVER).await().data?.let(::stickerNameFromDocument)
    }

    private inline fun <T> refusable(block: () -> T): T = try {
        block()
    } catch (e: FirebaseFirestoreException) {
        if (e.code == FirebaseFirestoreException.Code.PERMISSION_DENIED) throw StickerNamingRefusedException(e)
        throw e
    }

    internal companion object {
        const val COLLECTION = "stickerNames"
        const val FIELD_STATUS = "status"
    }
}

/** A request for a name: the three fields the rules allow, with the server's time. */
internal fun stickerNameRequest(format: String): Map<String, Any> = mapOf(
    FirestoreStickerNameSource.FIELD_STATUS to RemoteStickerName.STATUS_PENDING,
    "format" to format,
    "requestedAt" to FieldValue.serverTimestamp(),
)

/** The record in [data]. A document without a status reads as one with an unknown status. Nothing else is decided here. */
internal fun stickerNameFromDocument(data: Map<String, Any?>): RemoteStickerName =
    RemoteStickerName(status = data[FirestoreStickerNameSource.FIELD_STATUS] as? String ?: "", fields = data)
