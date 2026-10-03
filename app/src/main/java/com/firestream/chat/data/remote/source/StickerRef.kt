package com.firestream.chat.data.remote.source

import com.firestream.chat.domain.model.Message

/**
 * What a STICKER message points at, crossing the [MessageSource] boundary as
 * one value. Plaintext on the backend like `mediaUrl`: the file it names is
 * uploaded unencrypted.
 */
data class StickerRef(
    val id: String,
    val packId: String?,
) {
    companion object {
        /** [message]'s sticker fields, or `null` when it names no sticker. */
        fun of(message: Message): StickerRef? = message.stickerId?.let { StickerRef(it, message.stickerPackId) }
    }
}
