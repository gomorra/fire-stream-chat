package com.firestream.chat.data.remote.firebase

import android.net.Uri
import com.google.firebase.storage.FirebaseStorage
import kotlinx.coroutines.tasks.await
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class FirebaseStorageSource @Inject constructor(
    private val storage: FirebaseStorage
) {
    suspend fun uploadAvatar(userId: String, uri: Uri): String {
        val ref = storage.reference.child("avatars/$userId/profile.jpg")
        ref.putFile(uri).await()
        return ref.downloadUrl.await().toString()
    }

    suspend fun uploadMedia(chatId: String, messageId: String, uri: Uri, mimeType: String): String {
        val extension = mimeType.substringAfter("/")
        val ref = storage.reference.child("media/$chatId/$messageId.$extension")
        ref.putFile(uri).await()
        return ref.downloadUrl.await().toString()
    }
}
