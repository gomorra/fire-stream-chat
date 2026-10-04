package com.firestream.chat.data.remote.source

import java.io.File

/**
 * Backend-neutral home of sticker files. A sticker is one immutable object,
 * named by the SHA-256 of its bytes, shared by every message that points at it.
 */
interface StickerObjectSource {

    /**
     * The download url of the sticker [id], uploading [file] first when the
     * backend does not hold the object yet. The object is create-only, so a
     * sticker is uploaded once, by whoever sends it first, and never again.
     *
     * Nothing checks on the backend that the bytes match [id]. A receiver
     * verifies the hash of what it downloads (`StickerDownloads`).
     */
    suspend fun ensureUploaded(id: String, extension: String, mimeType: String, file: File): String

    /**
     * The download url of the sticker [id] when the backend holds its object,
     * else `null`. This is how a sticker known only by its id is fetched: the
     * object is found by the name the id gives it, never by a url someone sent.
     */
    suspend fun urlIfPresent(id: String, extension: String): String?
}
