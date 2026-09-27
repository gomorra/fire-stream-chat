package com.firestream.chat.domain.util

/**
 * Builds the preview a DOCUMENT bubble shows from a file already on the
 * device. Behind a domain interface so the chat screen reaches the platform
 * implementation (`data/util/FilePreviewLoader`) without a new UI→data import.
 */
interface FilePreviewSource {
    /** The preview cached for [path], if one was built and the file has not changed since. */
    fun cached(path: String): FilePreview?

    /** The preview of the local file at [path], classified as [kind]; never throws. */
    suspend fun load(path: String, kind: FileKind): FilePreview
}
