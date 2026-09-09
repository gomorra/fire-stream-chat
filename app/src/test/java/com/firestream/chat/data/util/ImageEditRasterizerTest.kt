package com.firestream.chat.data.util

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import com.firestream.chat.data.util.ImageEditRasterizer.RasterOp
import com.firestream.chat.ui.chat.PendingMedia
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/**
 * The rasterizer against real bitmaps and a real cache directory.
 *
 * Native graphics, because the whole point is that a decode-transform-encode
 * round trip produces the pixels the geometry promised — a legacy shadow bitmap
 * would report dimensions without ever doing the work.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [29], application = android.app.Application::class)
class ImageEditRasterizerTest {

    private lateinit var context: Context
    private lateinit var rasterizer: ImageEditRasterizer
    private lateinit var editsDir: File

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        rasterizer = ImageEditRasterizer(context, MediaProcessingLimiter())
        editsDir = File(context.cacheDir, ImageEditRasterizer.EDITS_DIR)
        editsDir.deleteRecursively()
    }

    /** A real JPEG on disk, [width] × [height], as a `file://` URI. */
    private fun sourceImage(width: Int, height: Int, name: String = "source.jpg"): Uri {
        val file = File(context.cacheDir, name)
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.JPEG, 90, it) }
        bitmap.recycle()
        return Uri.fromFile(file)
    }

    private fun dimensionsOf(uri: Uri): Pair<Int, Int> {
        val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        File(requireNotNull(uri.path)).inputStream().use {
            BitmapFactory.decodeStream(it, null, options)
        }
        return options.outWidth to options.outHeight
    }

    @Test
    fun `an empty op list still rasterizes a readable jpeg`() = runTest {
        val result = rasterizer.rasterize(sourceImage(120, 80), emptyList(), liveSteps = emptySet())

        assertEquals(120 to 80, dimensionsOf(result))
        assertTrue(rasterizer.exists(result))
        assertEquals(editsDir, File(requireNotNull(result.path)).parentFile)
    }

    @Test
    fun `a quarter turn swaps the output dimensions`() = runTest {
        val result = rasterizer.rasterize(sourceImage(120, 80), listOf(RasterOp.Rotate(90)), liveSteps = emptySet())

        assertEquals(80 to 120, dimensionsOf(result))
    }

    @Test
    fun `a flip keeps the output dimensions`() = runTest {
        val result = rasterizer.rasterize(sourceImage(120, 80), listOf(RasterOp.Flip(horizontal = true)), liveSteps = emptySet())

        assertEquals(120 to 80, dimensionsOf(result))
    }

    @Test
    fun `a crop writes only the requested fraction`() = runTest {
        val crop = RasterOp.Crop(left = 0f, top = 0.5f, right = 0.5f, bottom = 1f)

        val result = rasterizer.rasterize(sourceImage(120, 80), listOf(crop), liveSteps = emptySet())

        assertEquals(60 to 40, dimensionsOf(result))
    }

    @Test
    fun `a resize caps the long edge`() = runTest {
        val result = rasterizer.rasterize(sourceImage(120, 80), listOf(RasterOp.Resize(60)), liveSteps = emptySet())

        assertEquals(60 to 40, dimensionsOf(result))
    }

    @Test
    fun `ops apply in order, each to what the last one left`() = runTest {
        val ops = listOf(
            RasterOp.Rotate(90),
            RasterOp.Crop(left = 0f, top = 0f, right = 1f, bottom = 0.5f),
        )

        // 120x80 -> rotate -> 80x120 -> crop the top half -> 80x60.
        val result = rasterizer.rasterize(sourceImage(120, 80), ops, liveSteps = emptySet())

        assertEquals(80 to 60, dimensionsOf(result))
    }

    @Test
    fun `a source above the working ceiling is decoded down to it`() = runTest {
        // Not 108 MP — a fixture small enough for the test JVM that still
        // exceeds the real ceiling, so the production constant is what is under
        // test here, not a lowered stand-in.
        val ceiling = ImageEditRasterizer.WORKING_MAX_DIMENSION
        assertEquals(4096, ceiling)

        // 5120 x 1280 scales by exactly 0.8, so the expected output is arithmetic
        // rather than a rounding guess — and it stays small enough for the test JVM.
        val result = rasterizer.rasterize(sourceImage(5120, 1280, "huge.jpg"), emptyList(), liveSteps = emptySet())

        assertEquals(4096 to 1024, dimensionsOf(result))
    }

    @Test
    fun `each rasterize writes its own file`() = runTest {
        val source = sourceImage(60, 40)

        val first = rasterizer.rasterize(source, emptyList(), liveSteps = emptySet())
        val second = rasterizer.rasterize(first, listOf(RasterOp.Rotate(90)), liveSteps = emptySet())

        assertNotEquals(first, second)
        assertTrue(rasterizer.exists(first))
        assertTrue(rasterizer.exists(second))
        assertEquals(2, requireNotNull(editsDir.listFiles()).size)
    }

    @Test
    fun `discard deletes the steps it is given`() = runTest {
        val kept = rasterizer.rasterize(sourceImage(60, 40), emptyList(), liveSteps = emptySet())
        val abandoned = rasterizer.rasterize(sourceImage(60, 40), listOf(RasterOp.Rotate(180)), liveSteps = emptySet())

        rasterizer.discard(listOf(abandoned))

        assertTrue(rasterizer.exists(kept))
        assertFalse(rasterizer.exists(abandoned))
    }

    @Test
    fun `discard refuses anything that is not an edit-cache file`() = runTest {
        // The caller passes URIs straight out of an item's history, and every
        // other code path has the pick's own URI in that same shape. Deleting
        // the user's gallery original because a list got mixed up is not a
        // mistake worth leaving available.
        val pick = sourceImage(60, 40, "pick.jpg")
        val elsewhere = File(context.cacheDir, "compressed").apply { mkdirs() }
            .resolve("other.jpg").apply { writeText("bytes") }

        rasterizer.discard(listOf(pick, Uri.fromFile(elsewhere), Uri.parse("content://media/1")))

        assertTrue(File(requireNotNull(pick.path)).exists())
        assertTrue(elsewhere.exists())
    }

    @Test
    fun `exists reports a step the cache dropped`() = runTest {
        val step = rasterizer.rasterize(sourceImage(60, 40), emptyList(), liveSteps = emptySet())
        assertTrue(rasterizer.exists(step))

        File(requireNotNull(step.path)).delete()

        assertFalse(rasterizer.exists(step))
        // A content:// pick is not ours to stat; assume it is there and let the
        // send path report a real failure rather than silently dropping the item.
        assertTrue(rasterizer.exists(Uri.parse("content://media/external/images/1")))
    }

    @Test
    fun `the app-start sweep drops stale steps and keeps recent ones`() = runTest {
        val recent = rasterizer.rasterize(sourceImage(60, 40), emptyList(), liveSteps = emptySet())
        val stale = rasterizer.rasterize(sourceImage(60, 40), listOf(RasterOp.Rotate(180)), liveSteps = emptySet())
        File(requireNotNull(stale.path))
            .setLastModified(System.currentTimeMillis() - java.util.concurrent.TimeUnit.HOURS.toMillis(30))

        rasterizer.sweepStale()

        // Recent steps survive a restart so an interrupted send keeps its retry.
        assertTrue(rasterizer.exists(recent))
        assertFalse(rasterizer.exists(stale))
    }

    @Test
    fun `sweeping a cache that was never written is not an error`() {
        editsDir.deleteRecursively()

        rasterizer.sweepStale()

        assertFalse(editsDir.exists())
    }

    @Test
    fun `estimateSize caps a standard send and keeps an HD one at source size`() = runTest {
        val source = sourceImage(4000, 2000, "estimate.jpg")

        val standard = requireNotNull(rasterizer.estimateSize(source, hd = false))
        val hd = requireNotNull(rasterizer.estimateSize(source, hd = true))

        assertEquals(1600, standard.width)
        assertEquals(800, standard.height)
        assertEquals(4000, hd.width)
        assertEquals(2000, hd.height)
        // Standard is smaller in both dimensions and quality, so it must estimate
        // smaller — the whole reason the sheet shows two numbers.
        assertTrue(standard.bytes > 0)
        assertTrue(hd.bytes > standard.bytes)
    }

    @Test
    fun `estimateSize returns null for a URI it cannot read`() = runTest {
        assertNull(rasterizer.estimateSize(Uri.fromFile(File(context.cacheDir, "absent.jpg")), hd = false))
    }

    // ── the byte budget ──────────────────────────────────────────────────────

    /** A file of [bytes] in the edit cache, back-dated so ordering is decidable. */
    private fun step(name: String, bytes: Int, ageMillis: Long): File {
        editsDir.mkdirs()
        return File(editsDir, name).apply {
            writeBytes(ByteArray(bytes))
            setLastModified(System.currentTimeMillis() - ageMillis)
        }
    }

    @Test
    fun `the budget evicts oldest first until it is back under`() {
        val oldest = step("a.jpg", 400, ageMillis = 3_000)
        val middle = step("b.jpg", 400, ageMillis = 2_000)
        val newest = step("c.jpg", 400, ageMillis = 1_000)

        rasterizer.enforceBudget(budget = 500, keep = emptySet())

        assertFalse(oldest.exists())
        assertFalse(middle.exists())
        assertTrue(newest.exists())
    }

    @Test
    fun `the budget never evicts a step an item is currently on`() {
        // The regression this test exists for: eviction ranks the whole
        // directory oldest-first, so page 3's *current* step is old in global
        // terms while the user edits page 1 — and losing it loses an edit they
        // can still see, not just how far back undo reaches.
        val liveStepOfAnotherItem = step("a.jpg", 400, ageMillis = 3_000)
        val stale = step("b.jpg", 400, ageMillis = 2_000)
        val justWritten = step("c.jpg", 400, ageMillis = 0)

        rasterizer.enforceBudget(
            budget = 500,
            keep = setOf(liveStepOfAnotherItem, justWritten),
        )

        assertTrue(liveStepOfAnotherItem.exists())
        assertTrue(justWritten.exists())
        assertFalse(stale.exists())
    }

    @Test
    fun `a cache already under the budget is left alone`() {
        val only = step("a.jpg", 100, ageMillis = 5_000)

        rasterizer.enforceBudget(budget = 500, keep = emptySet())

        assertTrue(only.exists())
    }

    @Test
    fun `rasterize spares the live steps it is handed`() = runTest {
        val live = rasterizer.rasterize(sourceImage(60, 40), emptyList(), liveSteps = emptySet())
        val stale = step("stale.jpg", 400, ageMillis = 10_000)

        // A budget of zero forces eviction of everything evictable.
        rasterizer.enforceBudget(budget = 0, keep = setOf(File(requireNotNull(live.path))))

        assertTrue(rasterizer.exists(live))
        assertFalse(stale.exists())
    }

    // ── the two halves of "truncate and delete exactly the tail" ─────────────

    @Test
    fun `landing an edit hands back exactly the files that then get deleted`() = runTest {
        // PendingMedia decides *which* steps are abandoned and the rasterizer
        // deletes them; each half is specified on its own elsewhere, and this is
        // the seam between them — the place a correct list could still be
        // handed to the wrong deleter.
        val pick = sourceImage(60, 40, "pick.jpg")
        val steps = (1..3).map { rasterizer.rasterize(pick, emptyList(), liveSteps = emptySet()) }
        val item = PendingMedia(
            originalUri = pick,
            mimeType = "image/jpeg",
            editHistory = steps.map { it.toString() },
            editCursor = 1,
        )

        val landed = item.landEdit(rasterizer.rasterize(pick, emptyList(), liveSteps = emptySet()))
        rasterizer.discard(landed.abandoned.map(Uri::parse))

        assertTrue(rasterizer.exists(steps[0]))
        assertFalse(rasterizer.exists(steps[1]))
        assertFalse(rasterizer.exists(steps[2]))
        assertTrue(rasterizer.exists(landed.item.uri))
        // The pick itself is never a cache file and must survive regardless.
        assertTrue(File(requireNotNull(pick.path)).exists())
    }
}
