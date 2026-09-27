package com.firestream.chat.data.remote.source

import com.firestream.chat.domain.model.Message

/**
 * A DOCUMENT's identity as the sender picked it, crossing the [MessageSource]
 * boundary as one value. Plaintext on the backend like `mediaUrl` and
 * `duration`: the file it names is uploaded unencrypted today
 * (`docs/plans/file-handling.md` — privacy note).
 */
data class FileMetadata(
    val name: String?,
    val size: Long?,
    val mimeType: String?,
) {
    companion object {
        /** [message]'s file fields, or `null` when it carries none. */
        fun of(message: Message): FileMetadata? =
            if (message.fileName == null && message.fileSize == null && message.mimeType == null) {
                null
            } else {
                FileMetadata(message.fileName, message.fileSize, message.mimeType)
            }
    }
}
