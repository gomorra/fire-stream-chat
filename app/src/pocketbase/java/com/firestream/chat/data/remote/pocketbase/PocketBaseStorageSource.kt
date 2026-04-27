package com.firestream.chat.data.remote.pocketbase

import android.net.Uri
import com.firestream.chat.data.remote.source.StorageSource
import javax.inject.Inject
import javax.inject.Singleton

/** Step 4 stub. Stays a stub through v0; real impl is a follow-up plan. */
@Singleton
class PocketBaseStorageSource @Inject constructor() : StorageSource {
    override suspend fun uploadAvatar(userId: String, uri: Uri): String =
        throw NotImplementedError("PB v0 stub")

    override suspend fun uploadGroupAvatar(chatId: String, uri: Uri): String =
        throw NotImplementedError("PB v0 stub")

    override suspend fun uploadMedia(
        chatId: String,
        messageId: String,
        uri: Uri,
        mimeType: String,
        onProgress: ((Float) -> Unit)?
    ): String = throw NotImplementedError("PB v0 stub")
}
