package com.firestream.chat.data.sticker

import com.firestream.chat.test.LottieFixtures.animation
import com.firestream.chat.test.LottieFixtures.tgs
import com.firestream.chat.test.LottieFixtures.waProps
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** A Lottie sticker is read out of its container under a size cap and a nesting cap, and only when it is an animation. */
class LottieContainerTest {

    @Test
    fun `a tgs file is read as the animation it compresses`() {
        val json = animation(1, width = 512, height = 256)

        val read = LottieContainer.read(tgs(json))!!

        assertEquals(512, read.width)
        assertEquals(256, read.height)
        assertArrayEquals(json, read.json)
        assertNull(read.metadata)
    }

    @Test
    fun `the bare JSON of a was file is read with the pack and emojis WhatsApp wrote into it`() {
        val json = animation(2, customProps = waProps("SchoolDays", listOf("🚌", "👍")))

        val read = LottieContainer.read(json)!!

        assertArrayEquals(json, read.json)
        assertEquals("SchoolDays", read.metadata!!.packId)
        assertNull(read.metadata!!.packName)
        assertEquals(listOf("🚌", "👍"), read.metadata!!.emojis)
    }

    @Test
    fun `leading white space does not hide a JSON object`() {
        assertNotNull(LottieContainer.read("\n  ".toByteArray() + animation()))
    }

    @Test
    fun `compressing and inflating gives the JSON back, and the compressed bytes are a tgs file`() {
        val json = animation(3)

        val compressed = LottieContainer.compress(json)

        assertTrue(LottieContainer.isGzip(compressed))
        assertArrayEquals(json, LottieContainer.inflate(compressed))
        assertArrayEquals(json, LottieContainer.read(compressed)!!.json)
    }

    @Test
    fun `an animation that inflates past the cap is refused`() {
        // Highly compressible, as a gzip bomb is: a few kilobytes that unpack to more than the cap.
        val padding = "\"pad\":\"${"a".repeat(LottieContainer.MAX_JSON_BYTES)}\","
        val oversize = animation().let { json -> json.copyOfRange(0, 1) + padding.toByteArray() + json.copyOfRange(1, json.size) }
        val bomb = tgs(oversize)
        assertTrue("the file itself is small", bomb.size < StickerFiles.MAX_BYTES)

        assertNull(LottieContainer.inflate(bomb))
        assertNull(LottieContainer.read(bomb))
        assertNull(LottieContainer.read(oversize))
    }

    @Test
    fun `content that is not JSON is refused, in either container`() {
        assertNull(LottieContainer.read(tgs("hello".toByteArray())))
        assertNull(LottieContainer.read(tgs("[1,2,3]".toByteArray())))
        assertNull(LottieContainer.read(tgs("{\"w\":512,".toByteArray())))
        assertNull(LottieContainer.read("{not json".toByteArray()))
        assertNull(LottieContainer.read("hello".toByteArray()))
        assertNull(LottieContainer.read(ByteArray(0)))
    }

    @Test
    fun `a gzip stream that is cut short or malformed is refused`() {
        val whole = tgs()

        assertNull(LottieContainer.read(whole.copyOf(whole.size / 2)))
        assertNull(LottieContainer.read(byteArrayOf(0x1F, 0x8B.toByte(), 8, 0, 1, 2, 3)))
    }

    @Test
    fun `JSON that is not an animation is refused`() {
        assertNull("an object without layers", LottieContainer.read("""{"w":512,"h":512,"fr":60,"ip":0,"op":60}""".toByteArray()))
        assertNull("no layers", LottieContainer.read("""{"w":512,"h":512,"fr":60,"ip":0,"op":60,"layers":[]}""".toByteArray()))
        assertNull("no size", LottieContainer.read("""{"fr":60,"ip":0,"op":60,"layers":[{}]}""".toByteArray()))
        assertNull("no frame rate", LottieContainer.read("""{"w":512,"h":512,"ip":0,"op":60,"layers":[{}]}""".toByteArray()))
        assertNull("no frames", LottieContainer.read("""{"w":512,"h":512,"fr":60,"ip":60,"op":60,"layers":[{}]}""".toByteArray()))
        assertNotNull(LottieContainer.read("""{"w":512,"h":512,"fr":60,"ip":0,"op":60,"layers":[{}]}""".toByteArray()))
    }

    @Test
    fun `an animation larger than the dimension cap is refused`() {
        assertNull(LottieContainer.read(animation(width = StickerFiles.MAX_DIMENSION + 1)))
        assertNull(LottieContainer.read(animation(height = StickerFiles.MAX_DIMENSION + 1)))
        assertNull(LottieContainer.read(animation(width = 0)))
        assertNotNull(LottieContainer.read(animation(width = StickerFiles.MAX_DIMENSION, height = StickerFiles.MAX_DIMENSION)))
    }

    @Test
    fun `JSON nested deeper than the cap is refused before it is parsed`() {
        fun nested(depth: Int) = animation(extra = ""","deep":${"[".repeat(depth)}${"]".repeat(depth)}""")

        // The root object is one level, so the array may add one fewer than the cap.
        assertNotNull(LottieContainer.read(nested(LottieContainer.MAX_DEPTH - 1)))
        assertNull(LottieContainer.read(nested(LottieContainer.MAX_DEPTH)))
        assertNull("deep enough to overflow a recursive parser", LottieContainer.read(nested(200_000)))
    }

    @Test
    fun `brackets inside a string do not count as nesting`() {
        val brackets = "[{".repeat(LottieContainer.MAX_DEPTH * 2)

        assertNotNull(LottieContainer.read(animation(extra = ""","note":"$brackets \" \\\\"""")))
    }

    @Test
    fun `an animation that draws from an image is refused, and one with a precomposition is not`() {
        val embedded = """{"id":"image_0","w":40000,"h":40000,"u":"","p":"data:image/png;base64,AAAA","e":1}"""
        val linked = """{"id":"image_0","w":10,"h":10,"u":"images/","p":"img_0.png"}"""
        val precomposition = """{"id":"comp_0","layers":[]}"""

        assertNull(LottieContainer.read(animation(assets = embedded)))
        assertNull(LottieContainer.read(animation(assets = "$precomposition,$linked")))
        assertNotNull(LottieContainer.read(animation(assets = precomposition)))
    }

    @Test
    fun `an object that names a key twice is refused, however the second one is spelled`() {
        // Lottie reads both arrays and this parser the last one, so the second would hide the image in the first.
        val image = """"assets":[{"id":"image_0","w":40000,"h":40000,"p":"data:image/png;base64,AAAA","e":1}],"""
        fun withFirst(member: String) = animation().let { json -> json.copyOfRange(0, 1) + member.toByteArray() + json.copyOfRange(1, json.size) }

        assertNull(LottieContainer.read(withFirst(image)))
        assertNull(LottieContainer.read(withFirst(image.replace("\"assets\"", "\"\\u0061ssets\""))))
        assertNull(LottieContainer.read(animation(extra = ""","w":16""")))
        assertNull("in a nested object too", LottieContainer.read(animation(extra = ""","x":{"a":1,"b":[{"a":1}],"a":2}""")))
        // The scan itself, apart from what this JVM's JSON parser thinks of a repeated key.
        assertFalse(LottieContainer.hasPlainStructure("""{"a":1,"b":{"c":1},"a":2}""".toByteArray()))
        assertFalse("a key written with an escape", LottieContainer.hasPlainStructure(("{\"" + '\\' + "u0061\":1}").toByteArray()))
        assertTrue(LottieContainer.hasPlainStructure("""{"a":"a","b":["a","a"],"c":{"a":1}}""".toByteArray()))
        assertNotNull("the same key in two objects",LottieContainer.read(animation(extra = ""","x":[{"a":1},{"a":2}],"y":{"a":{"a":3}}""")))
    }

    @Test
    fun `layers are counted with every precomposition laid out`() {
        fun layer(refId: String) = """{"ty":0,"refId":"$refId"}"""
        fun precomposition(id: String, vararg refs: String) = """{"id":"$id","layers":[${refs.joinToString(",") { layer(it) }}]}"""
        fun withRootRef(assets: String) = String(animation(assets = assets)).replace("\"layers\":[{\"ddd\"", "\"layers\":[${layer("c0")},{\"ddd\"").toByteArray()

        // Twelve levels that each use the next one twice are 8190 layers from 24 lines of JSON.
        val doubling = (0 until 12).joinToString(",") { precomposition("c$it", "c${it + 1}", "c${it + 1}") }

        assertNotNull(LottieContainer.read(withRootRef(precomposition("c0", "c1") + "," + precomposition("c1"))))
        assertNull("refers to itself", LottieContainer.read(withRootRef(precomposition("c0", "c0"))))
        assertNull("a loop of two", LottieContainer.read(withRootRef(precomposition("c0", "c1") + "," + precomposition("c1", "c0"))))
        assertNull(LottieContainer.read(withRootRef(doubling)))
    }

    @Test
    fun `a gzip header is told from JSON and from a WebP`() {
        assertTrue(LottieContainer.isGzip(tgs()))
        assertFalse(LottieContainer.isGzip(animation()))
        assertTrue(LottieContainer.isJsonObject(animation()))
        assertFalse(LottieContainer.isJsonObject("RIFF".toByteArray()))
        assertFalse(LottieContainer.isJsonObject(ByteArray(0)))
    }
}
