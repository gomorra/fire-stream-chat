// region: AGENT-NOTE
// Responsibility: Firebase Storage uploads — user avatars, group avatars, message
//   media. Streams determinate progress for media uploads via
//   `addOnProgressListener` wrapped in `suspendCancellableCoroutine`.
// Owns: Storage path conventions: `avatars/{userId}/profile.jpg`,
//   `avatars/groups/{chatId}/group.jpg`, plaintext media under `media/{chatId}/{id}.{ext}`,
//   and encrypted 1:1 media/thumbnail blobs under `media/{chatId}/{storageId}.enc`
//   (`application/octet-stream`).
// Collaborators: ChatRepositoryImpl (group avatars), UserRepositoryImpl
//   (user avatars), OutboxSender (message media + uploadProgress flow).
// Don't put here: local media file management (MediaFileManager), image
//   compression (ImageCompressor), media backfill (MediaBackfillWorker).
// endregion

package com.firestream.chat.data.remote.firebase

import android.net.Uri
import com.firestream.chat.data.remote.source.StorageSource
import com.firestream.chat.data.util.DocumentFiles
import com.google.firebase.storage.FirebaseStorage
import com.google.firebase.storage.StorageMetadata
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.tasks.await
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

@Singleton
class FirebaseStorageSource @Inject constructor(
    private val storage: FirebaseStorage
) : StorageSource {
    override suspend fun uploadAvatar(userId: String, uri: Uri): String {
        val ref = storage.reference.child("avatars/$userId/profile.jpg")
        ref.putFile(uri).await()
        return ref.downloadUrl.await().toString()
    }

    override suspend fun uploadGroupAvatar(chatId: String, uri: Uri): String {
        val ref = storage.reference.child("avatars/groups/$chatId/group.jpg")
        ref.putFile(uri).await()
        return ref.downloadUrl.await().toString()
    }

    override suspend fun uploadMedia(
        chatId: String,
        messageId: String,
        uri: Uri,
        mimeType: String,
        onProgress: ((Float) -> Unit)?
    ): String {
        // The object name's extension is how a receiver without a mime type (a
        // message sent before `mimeType` was a field) types the file — so the
        // platform's extension for it, never the raw subtype ("plain",
        // "vnd.openxmlformats-officedocument…").
        val extension = DocumentFiles.extensionFor(fileName = null, mimeType = mimeType, mediaUrl = null)
        val ref = storage.reference.child("media/$chatId/$messageId.$extension")

        suspendCancellableCoroutine { cont ->
            val task = ref.putFile(uri)

            if (onProgress != null) {
                task.addOnProgressListener { snapshot ->
                    if (snapshot.totalByteCount > 0) {
                        onProgress(snapshot.bytesTransferred.toFloat() / snapshot.totalByteCount.toFloat())
                    }
                }
            }

            task.addOnSuccessListener { cont.resume(Unit) }
                .addOnFailureListener { cont.resumeWithException(it) }

            cont.invokeOnCancellation { task.cancel() }
        }

        return ref.downloadUrl.await().toString()
    }

    override suspend fun uploadEncryptedMedia(
        chatId: String,
        storageId: String,
        file: File,
        onProgress: ((Float) -> Unit)?,
    ): String {
        val ref = storage.reference.child("media/$chatId/$storageId.enc")
        val metadata = StorageMetadata.Builder()
            .setContentType("application/octet-stream")
            .build()

        suspendCancellableCoroutine { cont ->
            val task = ref.putFile(Uri.fromFile(file), metadata)

            if (onProgress != null) {
                task.addOnProgressListener { snapshot ->
                    if (snapshot.totalByteCount > 0) {
                        onProgress(snapshot.bytesTransferred.toFloat() / snapshot.totalByteCount.toFloat())
                    }
                }
            }

            task.addOnSuccessListener { cont.resume(Unit) }
                .addOnFailureListener { cont.resumeWithException(it) }

            cont.invokeOnCancellation { task.cancel() }
        }

        return ref.downloadUrl.await().toString()
    }
}
