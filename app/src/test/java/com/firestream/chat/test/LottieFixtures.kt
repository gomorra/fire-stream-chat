package com.firestream.chat.test

import java.io.ByteArrayOutputStream
import java.util.zip.GZIPOutputStream

/**
 * Hand-built Lottie animations for the sticker tests: one solid layer, which
 * Lottie parses and draws. The shapes follow the two containers a Lottie
 * sticker arrives in, and the WhatsApp one follows a real `.was` file.
 */
object LottieFixtures {

    /**
     * The animation JSON. [seed] makes the bytes, and so the id, differ.
     * [customProps] is the body of WhatsApp's `metadata.customProps` object.
     * [assets] is the body of the `assets` array, and [extra] is spliced in as
     * further top-level members.
     */
    fun animation(
        seed: Int = 0,
        width: Int = 512,
        height: Int = 512,
        customProps: String? = null,
        assets: String = "",
        extra: String = "",
    ): ByteArray {
        val metadata = customProps?.let { ""","metadata":{"customProps":{$it}}""" }.orEmpty()
        return (
            """{"v":"5.12.1","fr":60,"ip":0,"op":120,"w":$width,"h":$height,"nm":"sticker $seed","ddd":0,""" +
                """"assets":[$assets],"layers":[{"ddd":0,"ind":1,"ty":1,"nm":"solid","sr":1,""" +
                """"ks":{"o":{"a":0,"k":100},"r":{"a":0,"k":0},"p":{"a":0,"k":[256,256,0]},""" +
                """"a":{"a":0,"k":[256,256,0]},"s":{"a":0,"k":[100,100,100]}},""" +
                """"sw":512,"sh":512,"sc":"#ff0000","ip":0,"op":120,"st":0,"bm":0}]$extra$metadata}"""
            ).toByteArray(Charsets.UTF_8)
    }

    /** WhatsApp's `customProps` for a first-party sticker: a pack id without a name, and emojis. */
    fun waProps(packId: String, emojis: List<String>): String =
        """"sticker-pack-id":"$packId","is-first-party-sticker":1,"emojis":[${emojis.joinToString(",") { "\"$it\"" }}]"""

    /** [content] gzip-compressed, which is what a Telegram `.tgs` file is around its JSON. */
    fun tgs(content: ByteArray = animation()): ByteArray {
        val out = ByteArrayOutputStream()
        GZIPOutputStream(out).use { it.write(content) }
        return out.toByteArray()
    }

    /** A WhatsApp `.was` file: a zip with the animation and the token WhatsApp signs it with. */
    fun was(json: ByteArray = animation()): ByteArray = WebpFixtures.zip(
        "animation/" to ByteArray(0),
        "animation/animation.json" to json,
        "animation/animation.json.trust_token" to "token".toByteArray(),
    )
}
