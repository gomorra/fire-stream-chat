package com.firestream.chat.data.remote.source

import android.net.Uri
import java.io.File

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

    /**
     * Uploads an AES-256-GCM encrypted media or thumbnail blob to
     * `media/{chatId}/{storageId}.enc` with `application/octet-stream`.
     */
    suspend fun uploadEncryptedMedia(
        chatId: String,
        storageId: String,
        file: File,
        onProgress: ((Float) -> Unit)? = null,
    ): String
}
