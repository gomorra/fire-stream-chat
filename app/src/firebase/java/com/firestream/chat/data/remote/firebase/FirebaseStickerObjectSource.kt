// region: AGENT-NOTE
// Responsibility: Firebase Storage objects of sticker files — one per sticker,
//   `stickers/<sha256>.<ext>`, looked up first and uploaded only when missing.
// Owns: the `stickers/` path convention. The Storage rules make the object
//   create-only and are kept in the Firebase console, not in this repo.
// Collaborators: OutboxSender (the first send of a sticker), via the
//   StickerObjectSource interface in data/remote/source/.
// Don't put here: message media (FirebaseStorageSource — `media/<chatId>/`),
//   the local sticker directory (StickerFiles), the hash check of a downloaded
//   sticker (StickerDownloads).
// endregion

package com.firestream.chat.data.remote.firebase

import android.net.Uri
import com.firestream.chat.data.remote.source.StickerObjectSource
import com.google.firebase.storage.FirebaseStorage
import com.google.firebase.storage.StorageException
import com.google.firebase.storage.StorageMetadata
import com.google.firebase.storage.StorageReference
import kotlinx.coroutines.tasks.await
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class FirebaseStickerObjectSource @Inject constructor(
    private val storage: FirebaseStorage,
) : StickerObjectSource {

    override suspend fun ensureUploaded(id: String, extension: String, mimeType: String, file: File): String {
        val ref = storage.reference.child("$DIR/$id.$extension")
        urlIfPresent(ref)?.let { return it }
        val metadata = StorageMetadata.Builder().setContentType(mimeType).build()
        try {
            ref.putFile(Uri.fromFile(file), metadata).await()
        } catch (e: StorageException) {
            // Another device created the object after the lookup above. The rules
            // refuse to overwrite it, and the object that is there is the sticker.
            return urlIfPresent(ref) ?: throw e
        }
        return ref.downloadUrl.await().toString()
    }

    /** The object's download url, or `null` when the backend does not hold it. */
    private suspend fun urlIfPresent(ref: StorageReference): String? = try {
        ref.downloadUrl.await().toString()
    } catch (e: StorageException) {
        if (e.errorCode == StorageException.ERROR_OBJECT_NOT_FOUND) null else throw e
    }

    private companion object {
        const val DIR = "stickers"
    }
}
