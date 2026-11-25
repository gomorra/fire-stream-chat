// region: AGENT-NOTE
// Responsibility: Versioned (v=1) JSON envelope carried inside 1:1 Signal
//   ciphertext. Encapsulates message body, mentions, emoji sizes, reply target,
//   location coordinates, sticker reference, and media/thumbnail descriptors
//   (including AES-256-GCM keys, IVs, and ciphertext SHA-256 hashes) so
//   Firestore holds only routing and delivery state.
// Owns: MessageEnvelope schema (v=1), compact org.json serialization/parsing,
//   backward-compatible plain-string decoding for pre-Step-4 ciphertexts, and
//   merging decoded fields onto a RawMessage-derived domain Message.
// Collaborators: MessageWriter (encode), MessageRepositoryImpl (decode + applyTo).
// Don't put here: Signal encryption itself (SignalManager), media file
//   encryption (MediaCrypto), or Firestore serialization.
// endregion

package com.firestream.chat.data.crypto

import com.firestream.chat.domain.model.Message
import org.json.JSONArray
import org.json.JSONObject

data class MessageEnvelope(
    val v: Int = CURRENT_VERSION,
    val kind: Kind = Kind.MSG,
    val body: String = "",
    val mentions: List<String> = emptyList(),
    val emojiSizes: Map<Int, Float> = emptyMap(),
    val replyToId: String? = null,
    val location: LocationPayload? = null,
    val sticker: StickerPayload? = null,
    val media: MediaPayload? = null,
    val thumb: ThumbPayload? = null,
) {
    enum class Kind { MSG, EDIT }

    data class LocationPayload(
        val latitude: Double,
        val longitude: Double,
    )

    data class StickerPayload(
        val id: String,
        val packId: String? = null,
    )

    data class MediaPayload(
        val url: String? = null,
        val key: String? = null,
        val iv: String? = null,
        val sha256: String? = null,
        val width: Int? = null,
        val height: Int? = null,
        val duration: Int? = null,
        val isHd: Boolean = false,
        val fileName: String? = null,
        val fileSize: Long? = null,
        val mimeType: String? = null,
    )

    data class ThumbPayload(
        val url: String? = null,
        val key: String? = null,
        val iv: String? = null,
        val sha256: String? = null,
    )

    fun encode(): String {
        val obj = JSONObject()
        obj.put("v", v)
        obj.put("kind", kind.name)
        if (body.isNotEmpty()) obj.put("body", body)
        if (mentions.isNotEmpty()) obj.put("mentions", JSONArray(mentions))
        if (emojiSizes.isNotEmpty()) {
            val sizesObj = JSONObject()
            emojiSizes.forEach { (idx, size) ->
                if (size.isFinite()) sizesObj.put(idx.toString(), size.toDouble())
            }
            if (sizesObj.length() > 0) obj.put("emojiSizes", sizesObj)
        }
        if (replyToId != null) obj.put("replyToId", replyToId)
        location?.takeIf { it.latitude.isFinite() && it.longitude.isFinite() }?.let { loc ->
            obj.put(
                "location",
                JSONObject().apply {
                    put("lat", loc.latitude)
                    put("lng", loc.longitude)
                },
            )
        }
        sticker?.let { s ->
            obj.put(
                "sticker",
                JSONObject().apply {
                    put("id", s.id)
                    if (s.packId != null) put("packId", s.packId)
                },
            )
        }
        media?.let { m ->
            obj.put(
                "media",
                JSONObject().apply {
                    if (m.url != null) put("url", m.url)
                    if (m.key != null) put("key", m.key)
                    if (m.iv != null) put("iv", m.iv)
                    if (m.sha256 != null) put("sha256", m.sha256)
                    if (m.width != null) put("w", m.width)
                    if (m.height != null) put("h", m.height)
                    if (m.duration != null) put("dur", m.duration)
                    if (m.isHd) put("hd", true)
                    if (m.fileName != null) put("fileName", m.fileName)
                    if (m.fileSize != null) put("fileSize", m.fileSize)
                    if (m.mimeType != null) put("mimeType", m.mimeType)
                },
            )
        }
        thumb?.let { t ->
            obj.put(
                "thumb",
                JSONObject().apply {
                    if (t.url != null) put("url", t.url)
                    if (t.key != null) put("key", t.key)
                    if (t.iv != null) put("iv", t.iv)
                    if (t.sha256 != null) put("sha256", t.sha256)
                },
            )
        }
        return obj.toString()
    }

    /**
     * Overlays this envelope's decrypted fields onto [base] (typically built from
     * a Firestore `RawMessage`). Absent optional fields fall back to [base] so
     * pre-Step-4 encrypted messages whose metadata lived on the Firestore doc
     * continue to decode without data loss.
     */
    fun applyTo(base: Message): Message = base.copy(
        content = body,
        mentions = mentions.ifEmpty { base.mentions },
        emojiSizes = emojiSizes.ifEmpty { base.emojiSizes },
        replyToId = replyToId ?: base.replyToId,
        latitude = location?.latitude ?: base.latitude,
        longitude = location?.longitude ?: base.longitude,
        stickerId = sticker?.id ?: base.stickerId,
        stickerPackId = sticker?.packId ?: base.stickerPackId,
        mediaUrl = media?.url ?: base.mediaUrl,
        mediaKey = media?.key ?: base.mediaKey,
        mediaIv = media?.iv ?: base.mediaIv,
        mediaSha256 = media?.sha256 ?: base.mediaSha256,
        mediaWidth = media?.width ?: base.mediaWidth,
        mediaHeight = media?.height ?: base.mediaHeight,
        duration = media?.duration ?: base.duration,
        isHd = (media?.isHd == true) || base.isHd,
        fileName = media?.fileName ?: base.fileName,
        fileSize = media?.fileSize ?: base.fileSize,
        mimeType = media?.mimeType ?: base.mimeType,
        mediaThumbnailUrl = thumb?.url ?: base.mediaThumbnailUrl,
        thumbKey = thumb?.key ?: base.thumbKey,
        thumbIv = thumb?.iv ?: base.thumbIv,
        thumbSha256 = thumb?.sha256 ?: base.thumbSha256,
    )

    companion object {
        const val CURRENT_VERSION = 1

        fun fromMessage(message: Message): MessageEnvelope {
            val location = if (message.latitude != null && message.longitude != null) {
                LocationPayload(message.latitude, message.longitude)
            } else {
                null
            }
            val sticker = message.stickerId?.let { id ->
                StickerPayload(id = id, packId = message.stickerPackId)
            }
            val hasMedia = message.mediaUrl != null ||
                message.mediaKey != null ||
                message.mediaIv != null ||
                message.mediaSha256 != null ||
                message.mediaWidth != null ||
                message.mediaHeight != null ||
                message.duration != null ||
                message.isHd ||
                message.fileName != null ||
                message.fileSize != null ||
                message.mimeType != null
            val media = if (hasMedia) {
                MediaPayload(
                    url = message.mediaUrl,
                    key = message.mediaKey,
                    iv = message.mediaIv,
                    sha256 = message.mediaSha256,
                    width = message.mediaWidth,
                    height = message.mediaHeight,
                    duration = message.duration,
                    isHd = message.isHd,
                    fileName = message.fileName,
                    fileSize = message.fileSize,
                    mimeType = message.mimeType,
                )
            } else {
                null
            }
            val hasThumb = message.mediaThumbnailUrl != null ||
                message.thumbKey != null ||
                message.thumbIv != null ||
                message.thumbSha256 != null
            val thumb = if (hasThumb) {
                ThumbPayload(
                    url = message.mediaThumbnailUrl,
                    key = message.thumbKey,
                    iv = message.thumbIv,
                    sha256 = message.thumbSha256,
                )
            } else {
                null
            }
            return MessageEnvelope(
                v = CURRENT_VERSION,
                kind = Kind.MSG,
                body = message.content,
                mentions = message.mentions,
                emojiSizes = message.emojiSizes,
                replyToId = message.replyToId,
                location = location,
                sticker = sticker,
                media = media,
                thumb = thumb,
            )
        }

        fun forEdit(body: String, emojiSizes: Map<Int, Float> = emptyMap()): MessageEnvelope =
            MessageEnvelope(
                v = CURRENT_VERSION,
                kind = Kind.EDIT,
                body = body,
                emojiSizes = emojiSizes,
            )

        /**
         * Decodes a decrypted Signal payload. If [raw] is not a recognized v=1
         * JSON envelope (for example, a pre-Step-4 encrypted text message), it
         * is returned verbatim as `MessageEnvelope(body = raw)`.
         */
        fun decode(raw: String): MessageEnvelope {
            if (!raw.startsWith("{")) return MessageEnvelope(body = raw)
            return try {
                val obj = JSONObject(raw)
                val version = obj.opt("v") as? Int ?: return MessageEnvelope(body = raw)
                if (version != CURRENT_VERSION) return MessageEnvelope(body = raw)
                val kindStr = obj.optStringOrNull("kind") ?: return MessageEnvelope(body = raw)
                val kind = when (kindStr) {
                    Kind.MSG.name -> Kind.MSG
                    Kind.EDIT.name -> Kind.EDIT
                    else -> return MessageEnvelope(body = raw)
                }

                val mentionsArr = obj.optJSONArray("mentions")
                val mentions = if (mentionsArr != null) {
                    (0 until mentionsArr.length()).mapNotNull { i -> mentionsArr.opt(i) as? String }
                } else {
                    emptyList()
                }

                val emojiSizesObj = obj.optJSONObject("emojiSizes")
                val emojiSizes = if (emojiSizesObj != null) {
                    buildMap {
                        val keys = emojiSizesObj.keys()
                        while (keys.hasNext()) {
                            val k = keys.next()
                            val idx = k.toIntOrNull() ?: continue
                            val size = (emojiSizesObj.opt(k) as? Number)?.toDouble() ?: continue
                            if (size.isFinite()) put(idx, size.toFloat())
                        }
                    }
                } else {
                    emptyMap()
                }

                val location = obj.optJSONObject("location")?.let { loc ->
                    val lat = (loc.opt("lat") as? Number)?.toDouble()
                    val lng = (loc.opt("lng") as? Number)?.toDouble()
                    if (lat != null && lng != null && lat.isFinite() && lng.isFinite()) {
                        LocationPayload(lat, lng)
                    } else {
                        null
                    }
                }

                val sticker = obj.optJSONObject("sticker")?.let { s ->
                    val id = s.optStringOrNull("id") ?: return@let null
                    StickerPayload(id = id, packId = s.optStringOrNull("packId"))
                }

                val media = obj.optJSONObject("media")?.let { m ->
                    MediaPayload(
                        url = m.optStringOrNull("url"),
                        key = m.optStringOrNull("key"),
                        iv = m.optStringOrNull("iv"),
                        sha256 = m.optStringOrNull("sha256"),
                        width = m.optIntOrNull("w"),
                        height = m.optIntOrNull("h"),
                        duration = m.optIntOrNull("dur"),
                        isHd = m.opt("hd") as? Boolean ?: false,
                        fileName = m.optStringOrNull("fileName"),
                        fileSize = m.optLongOrNull("fileSize"),
                        mimeType = m.optStringOrNull("mimeType"),
                    )
                }

                val thumb = obj.optJSONObject("thumb")?.let { t ->
                    ThumbPayload(
                        url = t.optStringOrNull("url"),
                        key = t.optStringOrNull("key"),
                        iv = t.optStringOrNull("iv"),
                        sha256 = t.optStringOrNull("sha256"),
                    )
                }

                MessageEnvelope(
                    v = version,
                    kind = kind,
                    body = obj.optStringOrNull("body") ?: "",
                    mentions = mentions,
                    emojiSizes = emojiSizes,
                    replyToId = obj.optStringOrNull("replyToId"),
                    location = location,
                    sticker = sticker,
                    media = media,
                    thumb = thumb,
                )
            } catch (_: Exception) {
                MessageEnvelope(body = raw)
            }
        }

        private fun JSONObject.optStringOrNull(key: String): String? =
            opt(key) as? String

        private fun JSONObject.optIntOrNull(key: String): Int? =
            (opt(key) as? Number)?.toInt()

        private fun JSONObject.optLongOrNull(key: String): Long? =
            (opt(key) as? Number)?.toLong()
    }
}
