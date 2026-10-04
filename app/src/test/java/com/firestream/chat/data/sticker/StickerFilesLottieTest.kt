package com.firestream.chat.data.sticker

import android.content.Context
import android.graphics.BitmapFactory
import androidx.test.core.app.ApplicationProvider
import com.firestream.chat.domain.model.StickerFormat
import com.firestream.chat.test.LottieFixtures.animation
import com.firestream.chat.test.LottieFixtures.tgs
import com.firestream.chat.test.LottieFixtures.waProps
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * A Lottie sticker in the content-addressed directory: one stored shape for
 * both containers, and a first frame beside it. Native graphics, because the
 * first frame is really drawn.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [31], manifest = Config.NONE, application = android.app.Application::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class StickerFilesLottieTest {

    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val files = StickerFiles(context)

    @After
    fun tearDown() {
        context.filesDir.deleteRecursively()
    }

    private fun stored(): List<String> = files.dir.list()?.sorted().orEmpty()

    @Test
    fun `a tgs file is stored as it is under the hash of its bytes, with its first frame beside it`() = runTest {
        val bytes = tgs(animation(1))

        val stored = files.store(bytes)!!

        assertEquals(StickerFiles.sha256Hex(bytes), stored.id)
        assertEquals(StickerFormat.LOTTIE, stored.format)
        assertEquals(512, stored.width)
        assertEquals(512, stored.height)
        assertTrue(stored.isAnimated)
        assertTrue(stored.isNew)
        assertNull(stored.metadata)
        assertEquals(listOf("${stored.id}.tgs", "${stored.id}.tgs.png"), stored())
        assertArrayEquals(bytes, files.fileFor(stored.id, stored.format).readBytes())
    }

    @Test
    fun `the first frame is a picture of the animation, no larger than a grid needs`() = runTest {
        val stored = files.store(tgs(animation(2, width = 512, height = 256)))!!

        val still = BitmapFactory.decodeFile(files.stillFor(stored.id, stored.format).path)

        assertEquals(256, still.width)
        assertEquals(128, still.height)
        // The fixture is one red solid over the whole frame.
        assertEquals(0xFFFF0000.toInt(), still.getPixel(128, 64))
    }

    @Test
    fun `the JSON of a was file is stored compressed, and its id is the hash of the stored file`() = runTest {
        val json = animation(3, customProps = waProps("SchoolDays", listOf("🚌")))

        val stored = files.store(json)!!

        val file = files.fileFor(stored.id, StickerFormat.LOTTIE).readBytes()
        assertEquals(StickerFiles.sha256Hex(file), stored.id)
        assertTrue(LottieContainer.isGzip(file))
        assertArrayEquals(json, LottieContainer.inflate(file))
        assertEquals("SchoolDays", stored.metadata!!.packId)
        assertEquals(listOf("🚌"), stored.metadata!!.emojis)
    }

    @Test
    fun `the same animation a second time is not stored again, from either container`() = runTest {
        val json = animation(4)
        val first = files.store(json)!!

        val again = files.store(json)!!
        // What a recipient downloads is the stored file.
        val received = files.store(files.fileFor(first.id, first.format).readBytes())!!

        assertEquals(first.id, again.id)
        assertEquals(first.id, received.id)
        assertFalse(again.isNew)
        assertFalse(received.isNew)
        assertEquals(2, stored().size)
    }

    @Test
    fun `a first frame that went missing is drawn again at the next store`() = runTest {
        val bytes = tgs(animation(5))
        val first = files.store(bytes)!!
        val still = files.stillFor(first.id, first.format)
        still.delete()

        files.store(bytes)

        assertTrue(still.isFile)
    }

    @Test
    fun `an animation Lottie cannot draw is not a sticker, and nothing of it is written`() = runTest {
        val thumbnails = mockk<LottieThumbnails> { every { firstFrame(any()) } returns null }

        assertNull(StickerFiles(context, thumbnails).store(tgs(animation(6))))

        assertEquals(emptyList<String>(), stored())
    }

    @Test
    fun `Lottie itself is asked whether the animation holds an image or can be parsed`() {
        val thumbnails = LottieThumbnails()
        val image = """{"id":"image_0","w":16,"h":16,"u":"","p":"data:image/png;base64,AAAA","e":1}"""

        assertNull(thumbnails.firstFrame(animation(assets = image)))
        assertNull(thumbnails.firstFrame("not an animation".toByteArray()))
        assertNull(thumbnails.firstFrame(animation(width = StickerFiles.MAX_DIMENSION + 1)))
        assertTrue(thumbnails.firstFrame(animation())!!.isNotEmpty())
    }

    @Test
    fun `layers that refer to each other without end are refused and do not take the process down`() {
        // A precomposition layer that draws the precomposition it is in.
        val again = """{"ddd":0,"ind":9,"ty":0,"nm":"again","refId":"a","sr":1,""" +
            """"ks":{"o":{"a":0,"k":100},"r":{"a":0,"k":0},"p":{"a":0,"k":[0,0,0]},"a":{"a":0,"k":[0,0,0]},"s":{"a":0,"k":[100,100,100]}},""" +
            """"w":512,"h":512,"ip":0,"op":120,"st":0,"bm":0}"""
        val solid = "\"layers\":[{\"ddd\":0,\"ind\":1,\"ty\":1"
        val json = String(animation(assets = """{"id":"a","layers":[$again]}"""))
            .replace(solid, solid.replace("[", "[$again,"))
            .toByteArray()

        // The gate under LottieContainer: Lottie builds a layer per reference, recursively.
        assertNull(LottieThumbnails().firstFrame(json))
        assertNull(LottieContainer.read(json))
    }

    @Test
    fun `bare JSON is refused where the bytes must be stored as they are`() = runTest {
        val json = animation(8)

        assertNull(files.storeReceived(json))
        assertTrue(files.storeReceived(tgs(json))!!.isNew)
        assertEquals(1, stored().count { it.endsWith(".tgs") })
    }

    @Test
    fun `gzip and JSON that are not a Lottie animation are refused and leave nothing behind`() = runTest {
        assertNull(files.store(tgs("hello".toByteArray())))
        assertNull(files.store("""{"some":"json"}""".toByteArray()))
        assertNull(files.store(animation(width = StickerFiles.MAX_DIMENSION + 1)))

        assertEquals(emptyList<String>(), stored())
    }

    @Test
    fun `discard deletes the sticker and its first frame`() = runTest {
        val stored = files.store(tgs(animation(7)))!!

        files.discard(stored.id, stored.format)

        assertEquals(emptyList<String>(), stored())
    }

    @Test
    fun `a WebP is its own first frame`() {
        val id = "a".repeat(64)

        assertEquals(files.fileFor(id, StickerFormat.WEBP), files.stillFor(id, StickerFormat.WEBP))
    }
}
