package com.firestream.chat.data.remote.source

import android.net.Uri

/** Backend-neutral binary-blob storage boundary. */
interface StorageSource {
    suspend fun uploadAvatar(userId: String, uri: Uri): String
    suspend fun uploadGroupAvatar(chatId: String, uri: Uri): String
    suspend fun uploadMedia(
        chatId: String,
        messageId: String,
        uri: Uri,
        mimeType: String,
        onProgress: ((Float) -> Unit)? = null
    ): String
}
