// region: AGENT-NOTE
// Responsibility: Firebase Storage uploads — user avatars, group avatars, message
//   media. Streams determinate progress for media uploads via
//   `addOnProgressListener` wrapped in `suspendCancellableCoroutine`.
// Owns: Storage path conventions: `avatars/{userId}/profile.jpg`,
//   `avatars/groups/{chatId}/group.jpg`, message media under `messages/{chatId}/`.
// Collaborators: ChatRepositoryImpl (group avatars), UserRepositoryImpl
//   (user avatars), MessageRepositoryImpl (media + uploadProgress flow).
// Don't put here: local media file management (MediaFileManager), image
//   compression (ImageCompressor), media backfill (MediaBackfillWorker).
// endregion

package com.firestream.chat.data.remote.firebase

import android.net.Uri
import com.firestream.chat.data.remote.source.StorageSource
import com.google.firebase.storage.FirebaseStorage
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.tasks.await
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
        val extension = mimeType.substringAfter("/")
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
}
