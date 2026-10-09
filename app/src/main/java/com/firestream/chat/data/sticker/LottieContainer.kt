package com.firestream.chat.data.sticker

import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream

/** A Lottie animation that passed [LottieContainer]'s checks. [json] is the animation, not compressed. */
class LottieSticker(
    val width: Int,
    val height: Int,
    val json: ByteArray,
    val metadata: WaStickerMetadata?,
)

/**
 * Reads a Lottie sticker out of the containers it travels in.
 *
 * A Telegram `.tgs` is the animation JSON, gzip-compressed. A WhatsApp `.was`
 * is a zip that holds the JSON as `animation/animation.json`; `StickerPackArchive`
 * opens the zip and hands that entry here. The library stores one shape for
 * both: the gzip-compressed JSON.
 *
 * The input is untrusted. What it inflates to is capped, and so is how deeply
 * it nests: both JSON parsers the bytes will meet, this one and Lottie's own,
 * recurse per level. An animation that draws from image assets is refused. An
 * embedded image is decoded at parse time at whatever size it claims. The
 * layers are counted with every precomposition laid out, which is what Lottie
 * builds.
 *
 * These checks read the JSON with a parser that is not Lottie's. `LottieThumbnails`
 * is the second gate: it has Lottie parse and draw the animation, and
 * `StickerFiles` stores nothing that fails there.
 */
object LottieContainer {

    const val MAX_JSON_BYTES = 2 * 1024 * 1024
    internal const val MAX_DEPTH = 100
    internal const val MAX_LAYERS = 2_000
    private const val MAX_PRECOMPOSITION_DEPTH = 16
    private const val MAX_FRAME_RATE = 120.0

    private const val GZIP_ID1 = 0x1F.toByte()
    private const val GZIP_ID2 = 0x8B.toByte()

    /** Whether [bytes] start with a gzip header. */
    fun isGzip(bytes: ByteArray): Boolean = bytes.size > 2 && bytes[0] == GZIP_ID1 && bytes[1] == GZIP_ID2

    /** Whether [bytes] start like a JSON object, after any white space. */
    fun isJsonObject(bytes: ByteArray): Boolean {
        val first = bytes.firstOrNull { it != ' '.code.toByte() && it != '\n'.code.toByte() && it != '\r'.code.toByte() && it != '\t'.code.toByte() }
        return first == '{'.code.toByte()
    }

    /**
     * The animation in [bytes], which are a `.tgs` file or the bare JSON. Returns
     * `null` for anything else, and for an animation that breaks a limit. Nothing
     * here throws.
     */
    fun read(bytes: ByteArray): LottieSticker? {
        val json = when {
            isGzip(bytes) -> inflate(bytes) ?: return null
            isJsonObject(bytes) -> bytes
            else -> return null
        }
        return parse(json)
    }

    /** [json] as the library stores it. */
    fun compress(json: ByteArray): ByteArray {
        val out = ByteArrayOutputStream(json.size / 4 + 64)
        GZIPOutputStream(out).use { it.write(json) }
        return out.toByteArray()
    }

    /** What [gzip] inflates to, or `null` when it is malformed or holds more than [MAX_JSON_BYTES]. */
    fun inflate(gzip: ByteArray): ByteArray? = try {
        GZIPInputStream(ByteArrayInputStream(gzip)).use { input ->
            val out = ByteArrayOutputStream()
            val buffer = ByteArray(16 * 1024)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                if (out.size() + read > MAX_JSON_BYTES) return null
                out.write(buffer, 0, read)
            }
            out.toByteArray()
        }
    } catch (e: IOException) {
        null
    }

    private fun parse(json: ByteArray): LottieSticker? {
        if (json.size > MAX_JSON_BYTES || !hasPlainStructure(json)) return null
        val root = runCatching { JSONObject(String(json, Charsets.UTF_8)) }.getOrNull() ?: return null
        val width = root.optInt("w")
        val height = root.optInt("h")
        if (width !in 1..StickerFiles.MAX_DIMENSION || height !in 1..StickerFiles.MAX_DIMENSION) return null
        val frameRate = root.optDouble("fr")
        if (!(frameRate > 0.0 && frameRate <= MAX_FRAME_RATE)) return null
        if (!(root.optDouble("op") > root.optDouble("ip"))) return null
        val layers = root.optJSONArray("layers")
        if (layers == null || layers.length() == 0) return null
        val assets = root.optJSONArray("assets")
        if (assets.hasImage() || expandsPast(layers, assets, MAX_LAYERS)) return null
        return LottieSticker(width, height, json, WaStickerMetadata.ofLottie(root))
    }

    /** An image asset names its file in `p`. A precomposition has `layers` and no `p`. */
    private fun JSONArray?.hasImage(): Boolean {
        if (this == null) return false
        return (0 until length()).any { index -> optJSONObject(index)?.has("p") == true }
    }

    /**
     * Whether the animation has more than [max] layers once every
     * precomposition is laid out where a layer refers to it. Lottie builds one
     * layer object per reference, recursively, so a precomposition that refers
     * to itself never ends, and a few that each refer twice to the next double
     * with every level.
     */
    private fun expandsPast(layers: JSONArray, assets: JSONArray?, max: Int): Boolean {
        val precompositions = HashMap<String, JSONArray>()
        if (assets != null) {
            for (index in 0 until assets.length()) {
                val asset = assets.optJSONObject(index) ?: continue
                val assetLayers = asset.optJSONArray("layers") ?: continue
                precompositions[asset.optString("id")] = assetLayers
            }
        }
        val sizes = HashMap<String, Long>()
        val over = max + 1L

        fun size(of: JSONArray, depth: Int): Long {
            if (depth > MAX_PRECOMPOSITION_DEPTH) return over
            var total = 0L
            for (index in 0 until of.length()) {
                total += 1
                val refId = of.optJSONObject(index)?.optString("refId").orEmpty()
                val inner = precompositions[refId]
                if (inner != null) total += sizes[refId] ?: size(inner, depth + 1).also { sizes[refId] = it }
                if (total > max) return over
            }
            return total
        }
        return size(layers, depth = 0) > max
    }

    /**
     * A scan that runs before any parser sees the text. It refuses nesting
     * deeper than [MAX_DEPTH], and an object that names a key twice or writes a
     * key with an escape. The two parsers do not agree on a repeated key: this
     * one keeps the last value and Lottie's reads every one, so a second
     * `assets` could hide an image from the checks above.
     */
    internal fun hasPlainStructure(json: ByteArray): Boolean {
        // One entry per open bracket: the keys seen in an object, null for an array.
        val open = ArrayList<HashSet<String>?>()
        var keyNext = false
        var index = 0
        while (index < json.size) {
            when (json[index].toInt()) {
                '"'.code -> {
                    val start = index + 1
                    var end = start
                    var escaped = false
                    while (end < json.size && json[end].toInt() != '"'.code) {
                        if (json[end].toInt() == '\\'.code) {
                            escaped = true
                            end++
                        }
                        end++
                    }
                    if (end >= json.size) return false
                    if (keyNext) {
                        if (escaped || open.last()?.add(String(json, start, end - start, Charsets.UTF_8)) != true) return false
                        keyNext = false
                    }
                    index = end
                }
                '{'.code, '['.code -> {
                    if (open.size == MAX_DEPTH) return false
                    val isObject = json[index].toInt() == '{'.code
                    open += if (isObject) HashSet<String>() else null
                    keyNext = isObject
                }
                '}'.code, ']'.code -> {
                    if (open.isEmpty()) return false
                    open.removeAt(open.lastIndex)
                    keyNext = false
                }
                ','.code -> keyNext = open.lastOrNull() != null
            }
            index++
        }
        return open.isEmpty()
    }
}
