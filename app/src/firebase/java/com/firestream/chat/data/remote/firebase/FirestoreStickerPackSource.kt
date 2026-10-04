// region: AGENT-NOTE
// Responsibility: Firestore I/O for `stickerPacks/{packId}` — one document per
//   pack, holding the pack's fields and its stickers in order.
// Owns: the document shape (stickerPackToDocument / stickerPackFromDocument),
//   the listener on the owner's packs, the acknowledgement timeout of a write.
// Collaborators: StickerSyncWorker (writePack, deletePack), StickerLibrarySync
//   (observeOwnPacks), StickerRepositoryImpl (fetchPack), via the
//   StickerPackSource interface in data/remote/source/. `firestore.rules` lets
//   any signed-in user get a pack by id, and only its owner list and write.
// Don't put here: sticker files (FirebaseStickerObjectSource), whether a
//   manifest's content can be trusted (data/sticker/StickerManifest), the
//   newer-only merge into Room (StickerDao.applyRemotePack).
// endregion

package com.firestream.chat.data.remote.firebase

import com.firestream.chat.data.remote.source.RemoteSticker
import com.firestream.chat.data.remote.source.RemoteStickerPack
import com.firestream.chat.data.remote.source.StickerPackChanges
import com.firestream.chat.data.remote.source.StickerPackSource
import com.google.firebase.firestore.DocumentChange
import com.google.firebase.firestore.FirebaseFirestore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.asExecutor
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.buffer
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.tasks.await
import java.net.URLDecoder
import java.net.URLEncoder
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class FirestoreStickerPackSource @Inject constructor(
    private val firestore: FirebaseFirestore,
) : StickerPackSource {

    private val packs get() = firestore.collection(COLLECTION)

    /**
     * Where the listener's snapshots are mapped. Off the main thread: a manifest
     * lists thousands of stickers. One at a time: each snapshot is a difference
     * to the one before, so they must reach the flow in the order they came.
     */
    private val snapshotExecutor = Dispatchers.Default.limitedParallelism(1).asExecutor()

    override val isSupported: Boolean = true

    override fun observeOwnPacks(ownerId: String): Flow<StickerPackChanges> = callbackFlow {
        val listener = packs.whereEqualTo(FIELD_OWNER_ID, ownerId)
            .addSnapshotListener(snapshotExecutor) { snapshot, error ->
                if (error != null) {
                    close(error)
                    return@addSnapshotListener
                }
                val changes = snapshot?.documentChanges.orEmpty()
                val (removed, upserted) = changes.partition { it.type == DocumentChange.Type.REMOVED }
                if (changes.isNotEmpty()) {
                    trySend(
                        StickerPackChanges(
                            upserted = upserted.mapNotNull { stickerPackFromDocument(it.document.id, it.document.data) },
                            removedIds = removed.map { it.document.id },
                        )
                    )
                }
            }
        awaitClose { listener.remove() }
    }
        // Every emission is a difference, so one dropped by a full buffer would be lost for good.
        .buffer(Channel.UNLIMITED)

    override suspend fun fetchPack(packId: String): RemoteStickerPack? =
        packs.document(packId).get().await().data?.let { stickerPackFromDocument(packId, it) }

    // Both writes wait for the server under awaitAck. The SDK keeps a write that timed out
    // and sends it later, and writing or deleting the same manifest again is harmless.

    override suspend fun writePack(pack: RemoteStickerPack) = awaitAck("Sticker pack ${pack.id}") {
        packs.document(pack.id).set(stickerPackToDocument(pack)).await()
    }

    override suspend fun deletePack(packId: String) = awaitAck("Delete of sticker pack $packId") {
        packs.document(packId).delete().await()
    }

    internal companion object {
        const val COLLECTION = "stickerPacks"
        const val FIELD_OWNER_ID = "ownerId"
    }
}

private const val KEY_CHARSET = "UTF-8"

internal fun stickerPackToDocument(pack: RemoteStickerPack): Map<String, Any?> = mapOf(
    FirestoreStickerPackSource.FIELD_OWNER_ID to pack.ownerId,
    "name" to pack.name,
    "publisher" to pack.publisher,
    "kind" to pack.kind,
    "originPackId" to pack.originPackId,
    // Percent-encoded: an import key joins its parts with U+0000, which is kept out of a stored string.
    "importKey" to pack.importKey?.let { URLEncoder.encode(it, KEY_CHARSET) },
    "sortOrder" to pack.sortOrder,
    "createdAt" to pack.createdAt,
    "updatedAt" to pack.updatedAt,
    "stickers" to pack.stickers.map { sticker ->
        mapOf(
            "id" to sticker.id,
            "format" to sticker.format,
            "width" to sticker.width,
            "height" to sticker.height,
            "animated" to sticker.isAnimated,
            "emojis" to sticker.emojis,
        )
    },
)

/**
 * The manifest in [data], or `null` for a document without an owner. Fields of
 * the wrong type read as absent, and a sticker entry without an id is left out.
 * Whether the values are acceptable is not decided here.
 */
internal fun stickerPackFromDocument(id: String, data: Map<String, Any?>): RemoteStickerPack? {
    val ownerId = data[FirestoreStickerPackSource.FIELD_OWNER_ID] as? String ?: return null
    return RemoteStickerPack(
        id = id,
        ownerId = ownerId,
        name = data["name"] as? String ?: "",
        publisher = data["publisher"] as? String,
        kind = data["kind"] as? String ?: "",
        originPackId = data["originPackId"] as? String,
        importKey = (data["importKey"] as? String)?.let { runCatching { URLDecoder.decode(it, KEY_CHARSET) }.getOrNull() },
        sortOrder = (data["sortOrder"] as? Number)?.toInt() ?: 0,
        createdAt = (data["createdAt"] as? Number)?.toLong() ?: 0L,
        updatedAt = (data["updatedAt"] as? Number)?.toLong() ?: 0L,
        stickers = (data["stickers"] as? List<*>).orEmpty().mapNotNull { entry ->
            val sticker = entry as? Map<*, *> ?: return@mapNotNull null
            RemoteSticker(
                id = sticker["id"] as? String ?: return@mapNotNull null,
                format = sticker["format"] as? String ?: "",
                width = (sticker["width"] as? Number)?.toInt() ?: 0,
                height = (sticker["height"] as? Number)?.toInt() ?: 0,
                isAnimated = sticker["animated"] as? Boolean ?: false,
                emojis = (sticker["emojis"] as? List<*>).orEmpty().filterIsInstance<String>(),
            )
        },
    )
}
