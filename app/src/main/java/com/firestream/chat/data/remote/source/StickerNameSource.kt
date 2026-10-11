package com.firestream.chat.data.remote.source

/**
 * One sticker's naming record as the backend holds it: a request, or what the
 * naming service made of it.
 *
 * Nothing in it is checked. [status] is whatever the record says, and [fields]
 * are its other fields as they were read, of whatever type. The naming service
 * looks at a picture whose text may be written to steer it, so
 * `StickerNameAnswer` checks an answer before any of it reaches a row.
 */
data class RemoteStickerName(
    val status: String,
    val fields: Map<String, Any?> = emptyMap(),
) {
    companion object {
        /** Asked for, and not answered yet. */
        const val STATUS_PENDING = "pending"

        /** Answered. [fields] hold the names, the words, the text and the emojis. */
        const val STATUS_DONE = "done"

        /** The service cannot name this sticker. */
        const val STATUS_SKIPPED = "skipped"
    }
}

/**
 * The backend refuses to say or take anything about sticker names: its rules
 * are not deployed, or naming is not offered there. It is no failure of one
 * sticker, and trying the next one gives the same answer.
 */
class StickerNamingRefusedException(cause: Throwable? = null) : Exception("Sticker naming is not available", cause)

/**
 * Backend-neutral home of the naming records, one per sticker id.
 *
 * A record belongs to the sticker and to no user: every phone that holds the
 * sticker reads the same answer, so a sticker is named once. A phone only ever
 * creates a request and reads one record at a time. There is no way to list
 * them, because a sticker id is all it takes to fetch the sticker's file.
 */
interface StickerNameSource {

    /** False for a backend without a naming service. Nothing is asked for then. */
    val isSupported: Boolean

    /**
     * Asks for a name for the sticker [stickerId], whose file is of [format].
     * Writes nothing when the backend already holds a record for it, whatever
     * that record says. Returns once the backend has answered.
     *
     * The request carries the id and the format and nothing else: no user, no
     * pack, nothing a person typed.
     *
     * @throws StickerNamingRefusedException when the backend refuses.
     */
    suspend fun request(stickerId: String, format: String)

    /**
     * The record of [stickerId] as the backend holds it now, or `null` when it
     * has none. It is read from the backend, never from a local copy.
     *
     * @throws StickerNamingRefusedException when the backend refuses.
     */
    suspend fun fetch(stickerId: String): RemoteStickerName?
}
