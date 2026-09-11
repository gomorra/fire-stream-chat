package com.firestream.chat.data.util

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import com.firestream.chat.domain.util.ImageEditGeometry
import com.firestream.chat.domain.util.ImageOverlay
import com.firestream.chat.domain.util.OverlayContent
import com.firestream.chat.domain.util.RasterOp
import com.firestream.chat.domain.util.ShapeKind
import com.firestream.chat.domain.util.Stroke
import com.firestream.chat.domain.util.StrokeGeometry
import com.firestream.chat.domain.util.StrokePoint
import com.firestream.chat.domain.util.StrokeTool
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

    /** A solid [color] PNG, so the fixture's own pixels carry no JPEG artefacts. */
    private fun flatSource(width: Int, height: Int, color: Int, name: String): Uri =
        pngSource(width, height, name) { bitmap ->
            Canvas(bitmap).drawColor(color)
        }

    /** Vertical black-and-white stripes [stripe] pixels wide — detail to destroy. */
    private fun stripedSource(width: Int, height: Int, stripe: Int, name: String): Uri =
        pngSource(width, height, name) { bitmap ->
            val canvas = Canvas(bitmap)
            canvas.drawColor(Color.WHITE)
            val paint = Paint().apply { color = Color.BLACK }
            var x = 0
            while (x < width) {
                canvas.drawRect(x.toFloat(), 0f, (x + stripe).toFloat(), height.toFloat(), paint)
                x += stripe * 2
            }
        }

    private fun pngSource(width: Int, height: Int, name: String, paint: (Bitmap) -> Unit): Uri {
        val file = File(context.cacheDir, name)
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        paint(bitmap)
        file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        bitmap.recycle()
        return Uri.fromFile(file)
    }

    private fun decode(uri: Uri): Bitmap =
        requireNotNull(BitmapFactory.decodeFile(requireNotNull(uri.path))) { "unreadable output $uri" }

    /**
     * How far apart the brightest and darkest pixels are along one row — the
     * measure of whether detail survived. A run of stripes scores high; a flat
     * mosaic block scores near zero.
     */
    private fun contrastAcross(bitmap: Bitmap, y: Int, fromX: Int, toX: Int): Int {
        val luminance = (fromX until toX).map { x -> Color.red(bitmap.getPixel(x, y)) }
        return (luminance.max() - luminance.min())
    }

    private fun assertRed(pixel: Int) {
        assertTrue(
            "expected the stroke's red, got #${Integer.toHexString(pixel)}",
            Color.red(pixel) > 150 && Color.green(pixel) < 100 && Color.blue(pixel) < 100,
        )
    }

    private fun assertWhite(what: String, pixel: Int) {
        assertTrue(
            "expected $what to stay white, got #${Integer.toHexString(pixel)}",
            Color.red(pixel) > 200 && Color.green(pixel) > 200 && Color.blue(pixel) > 200,
        )
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
        val ceiling = ImageEditGeometry.WORKING_MAX_DIMENSION
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

    // ── Originals copied in from the fullscreen viewer (Phase 6) ──

    private fun sentPhoto(bytes: ByteArray = byteArrayOf(1, 2, 3, 4)): File =
        File(context.cacheDir, "sent.jpg").apply { writeBytes(bytes) }

    @Test
    fun `importing a sent photo copies it and leaves the photo itself alone`() = runTest {
        val sent = sentPhoto()

        val imported = rasterizer.importSource(sent)

        val copy = File(requireNotNull(imported.path))
        assertNotEquals(sent.canonicalPath, copy.canonicalPath)
        assertEquals(
            File(editsDir, ImageEditRasterizer.SOURCES_DIR).canonicalPath,
            copy.parentFile?.canonicalPath,
        )
        assertTrue(copy.readBytes().contentEquals(sent.readBytes()))
        assertEquals("jpg", copy.extension)
    }

    @Test
    fun `discard collects an imported original but never the photo it was copied from`() = runTest {
        val sent = sentPhoto()
        val imported = rasterizer.importSource(sent)

        rasterizer.discard(listOf(imported, Uri.fromFile(sent)))

        assertFalse(File(requireNotNull(imported.path)).exists())
        assertTrue(sent.exists())
    }

    @Test
    fun `the budget never evicts an imported original`() = runTest {
        // The original is the oldest file of its batch and stops being anyone's
        // current step as soon as the first edit lands, so an eviction that could
        // see it would take the one file revert falls back to.
        val imported = rasterizer.importSource(sentPhoto())
        val original = File(requireNotNull(imported.path))
        original.setLastModified(System.currentTimeMillis() - 60_000)
        File(editsDir, "edit_step.jpg").writeBytes(ByteArray(1_000))

        rasterizer.enforceBudget(budget = 0, keep = emptySet())

        assertTrue(original.exists())
    }

    @Test
    fun `the app-start sweep collects a stale imported original`() = runTest {
        val stale = File(requireNotNull(rasterizer.importSource(sentPhoto()).path))
        stale.setLastModified(System.currentTimeMillis() - java.util.concurrent.TimeUnit.HOURS.toMillis(30))
        val recent = File(requireNotNull(rasterizer.importSource(sentPhoto()).path))

        rasterizer.sweepStale()

        assertFalse(stale.exists())
        assertTrue(recent.exists())
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

    // ── two rasterize calls at once ──────────────────────────────────────────

    @Test
    fun `a live step survives an impossible budget`() = runTest {
        val live = rasterizer.rasterize(sourceImage(60, 40), emptyList(), liveSteps = emptySet())
        rasterizer.cacheBudgetBytes = 1

        // Nothing left to delete but the protected file: the cache stays over
        // budget rather than the user losing the step they are looking at.
        val next = rasterizer.rasterize(sourceImage(60, 40), emptyList(), liveSteps = setOf(live))

        assertTrue(rasterizer.exists(live))
        assertTrue(rasterizer.exists(next))
    }

    // ── Straighten ────────────────────────────────────────────────────────────

    @Test
    fun `a straighten crops back to a full rectangle of the same aspect ratio`() = runTest {
        // The output must never be the expanded canvas with black triangles in
        // it: the auto-crop is the op's contract, not a follow-up step.
        val result = rasterizer.rasterize(
            source = sourceImage(400, 300, "straighten-source.jpg"),
            ops = listOf(RasterOp.Straighten(10f)),
            liveSteps = emptySet(),
        )

        val (width, height) = dimensionsOf(result)
        assertEquals(ImageEditGeometry.straightenSize(400, 300, 10f), width to height)
        assertTrue("a straighten always costs pixels", width < 400)
        assertEquals(400f / 300f, width.toFloat() / height, 0.02f)
    }

    @Test
    fun `a zero-degree straighten writes the image through unchanged`() = runTest {
        val result = rasterizer.rasterize(
            source = sourceImage(320, 240, "straighten-zero.jpg"),
            ops = listOf(RasterOp.Straighten(0f)),
            liveSteps = emptySet(),
        )

        assertEquals(320 to 240, dimensionsOf(result))
    }

    @Test
    fun `a straighten past the limit is clamped rather than inverted`() = runTest {
        val result = rasterizer.rasterize(
            source = sourceImage(400, 400, "straighten-clamped.jpg"),
            ops = listOf(RasterOp.Straighten(400f)),
            liveSteps = emptySet(),
        )

        val (width, height) = dimensionsOf(result)
        assertEquals(ImageEditGeometry.straightenSize(400, 400, 45f), width to height)
        assertTrue(width > 0 && height > 0)
    }

    @Test
    fun `a straighten fills its output rather than leaving transparent corners`() = runTest {
        // The pathology this op exists to avoid, checked in pixels: every corner
        // of the written file must be opaque photo, not the empty canvas a
        // rotate-then-crop would leave behind if the arithmetic were wrong.
        val source = File(context.cacheDir, "straighten-opaque.jpg")
        val painted = Bitmap.createBitmap(400, 300, Bitmap.Config.ARGB_8888)
        painted.eraseColor(android.graphics.Color.RED)
        source.outputStream().use { painted.compress(Bitmap.CompressFormat.JPEG, 95, it) }
        painted.recycle()

        val result = rasterizer.rasterize(
            source = Uri.fromFile(source),
            ops = listOf(RasterOp.Straighten(12f)),
            liveSteps = emptySet(),
        )

        val output = BitmapFactory.decodeFile(requireNotNull(result.path))
        val corners = listOf(
            0 to 0,
            output.width - 1 to 0,
            0 to output.height - 1,
            output.width - 1 to output.height - 1,
        )
        for ((x, y) in corners) {
            val pixel = output.getPixel(x, y)
            assertTrue("corner ($x, $y) is transparent", android.graphics.Color.alpha(pixel) == 255)
            assertTrue(
                "corner ($x, $y) is black rather than photo",
                android.graphics.Color.red(pixel) > 100,
            )
        }
        output.recycle()
    }

    // ── The preview render ────────────────────────────────────────────────────

    @Test
    fun `a preview applies the same ops the rasterize would, at screen size`() = runTest {
        val source = sourceImage(2000, 1000, "preview-source.jpg")
        val ops = listOf(RasterOp.Rotate(90), RasterOp.Crop(0f, 0f, 1f, 0.5f))

        val preview = requireNotNull(rasterizer.preview(source, ops, maxDimension = 400))

        // Same shape as the flattened result, only smaller — which is the whole
        // promise: what the editor shows is what Done writes, scaled.
        val (flattenedWidth, flattenedHeight) =
            dimensionsOf(rasterizer.rasterize(source, ops, liveSteps = emptySet()))
        assertEquals(
            flattenedWidth.toFloat() / flattenedHeight,
            preview.width.toFloat() / preview.height,
            0.05f,
        )
        assertTrue("the preview must respect its ceiling", maxOf(preview.width, preview.height) <= 400)
        preview.recycle()
    }

    @Test
    fun `a preview writes nothing to the edit cache`() = runTest {
        rasterizer.preview(sourceImage(300, 200, "preview-clean.jpg"), emptyList(), maxDimension = 200)
            ?.recycle()

        assertTrue(editsDir.listFiles().orEmpty().none { it.isFile })
    }

    @Test
    fun `an unreadable source previews as null rather than throwing`() = runTest {
        assertNull(rasterizer.preview(Uri.parse("file:///nope/missing.jpg"), emptyList(), 400))
    }

    // ── Strokes ───────────────────────────────────────────────────────────────

    @Test
    fun `a pen stroke paints its colour into the file, and only where it was drawn`() = runTest {
        val stroke = Stroke(
            tool = StrokeTool.PEN,
            colorArgb = 0xFFFF0000,
            width = 0.08f,
            points = listOf(StrokePoint(0.1f, 0.5f), StrokePoint(0.9f, 0.5f)),
        )

        val result = rasterizer.rasterize(
            flatSource(240, 240, Color.WHITE, "pen-source.png"),
            listOf(RasterOp.Strokes(listOf(stroke))),
            liveSteps = emptySet(),
        )

        val output = decode(result)
        assertRed(output.getPixel(120, 120))
        assertWhite("a corner the stroke never reached", output.getPixel(10, 10))
        assertEquals(240 to 240, output.width to output.height)
        output.recycle()
    }

    @Test
    fun `a tap with no drag lands as a dot rather than as nothing at all`() = runTest {
        val tap = Stroke(
            tool = StrokeTool.PEN,
            colorArgb = 0xFFFF0000,
            width = 0.2f,
            points = listOf(StrokePoint(0.5f, 0.5f)),
        )

        val result = rasterizer.rasterize(
            flatSource(240, 240, Color.WHITE, "dot-source.png"),
            listOf(RasterOp.Strokes(listOf(tap))),
            liveSteps = emptySet(),
        )

        val output = decode(result)
        assertRed(output.getPixel(120, 120))
        assertWhite("outside the dot", output.getPixel(10, 10))
        output.recycle()
    }

    @Test
    fun `an empty drawing writes the photo through untouched`() = runTest {
        val result = rasterizer.rasterize(
            flatSource(120, 80, Color.WHITE, "empty-strokes.png"),
            listOf(RasterOp.Strokes(emptyList())),
            liveSteps = emptySet(),
        )

        assertEquals(120 to 80, dimensionsOf(result))
        val output = decode(result)
        assertWhite("an empty drawing changes nothing", output.getPixel(60, 40))
        output.recycle()
    }

    @Test
    fun `a blur stroke destroys the detail it covers and leaves the rest sharp`() = runTest {
        // Four-pixel stripes under a mosaic whose blocks are ten pixels wide:
        // inside the stroke the alternation has to be gone, not merely softened,
        // because "gone" is the whole claim the tool makes when someone is
        // hiding a face or a bank card.
        val blur = Stroke(
            tool = StrokeTool.BLUR,
            colorArgb = 0,
            width = 0.3f,
            points = listOf(StrokePoint(0.1f, 0.5f), StrokePoint(0.9f, 0.5f)),
        )

        val result = rasterizer.rasterize(
            stripedSource(480, 480, stripe = 4, name = "blur-source.png"),
            listOf(RasterOp.Strokes(listOf(blur))),
            liveSteps = emptySet(),
        )

        val output = decode(result)
        val covered = contrastAcross(output, y = 240, fromX = 200, toX = 280)
        val untouched = contrastAcross(output, y = 40, fromX = 200, toX = 280)
        assertTrue("the stripes must survive outside the stroke (was $untouched)", untouched > 120)
        assertTrue("the stripes must be gone inside it (was $covered)", covered < 60)
        output.recycle()
    }

    @Test
    fun `a blur drawn afterwards does not swallow the mark that pointed at it`() = runTest {
        // Blur redacts the photo, pen annotates it, so the pen goes on top
        // whatever order the two were drawn in (`StrokeGeometry.layers`).
        val pen = Stroke(
            tool = StrokeTool.PEN,
            colorArgb = 0xFFFF0000,
            width = 0.08f,
            points = listOf(StrokePoint(0.1f, 0.5f), StrokePoint(0.9f, 0.5f)),
        )
        val blurOverIt = Stroke(
            tool = StrokeTool.BLUR,
            colorArgb = 0,
            width = 0.4f,
            points = listOf(StrokePoint(0.1f, 0.5f), StrokePoint(0.9f, 0.5f)),
        )

        val result = rasterizer.rasterize(
            flatSource(240, 240, Color.WHITE, "order-source.png"),
            listOf(RasterOp.Strokes(listOf(pen, blurOverIt))),
            liveSteps = emptySet(),
        )

        val output = decode(result)
        assertRed(output.getPixel(120, 120))
        output.recycle()
    }

    @Test
    fun `a highlighter leaves what is under it legible where a pen would not`() = runTest {
        val yellow = 0xFFFFFF00
        fun mark(tool: StrokeTool) = Stroke(
            tool = tool,
            colorArgb = yellow,
            width = 0.15f,
            points = listOf(StrokePoint(0.1f, 0.5f), StrokePoint(0.9f, 0.5f)),
        )

        val source = stripedSource(240, 240, stripe = 8, name = "highlighter-source.png")
        val highlighted = decode(
            rasterizer.rasterize(source, listOf(RasterOp.Strokes(listOf(mark(StrokeTool.HIGHLIGHTER)))), emptySet()),
        )
        val penned = decode(
            rasterizer.rasterize(source, listOf(RasterOp.Strokes(listOf(mark(StrokeTool.PEN)))), emptySet()),
        )

        val underHighlighter = contrastAcross(highlighted, y = 120, fromX = 100, toX = 180)
        val underPen = contrastAcross(penned, y = 120, fromX = 100, toX = 180)
        assertTrue("a pen covers what it crosses (was $underPen)", underPen < 40)
        assertTrue(
            "a highlighter must not (was $underHighlighter, pen was $underPen)",
            underHighlighter > underPen + 60,
        )
        highlighted.recycle()
        penned.recycle()
    }

    @Test
    fun `the mosaic is the same fraction of the photo whatever size the photo is`() = runTest {
        // The property the editor's preview depends on: it pixelates a
        // screen-sized bitmap and promises that the full-resolution flatten
        // redacts the same region, at the same coarseness.
        val small = Bitmap.createBitmap(600, 400, Bitmap.Config.ARGB_8888)
        val large = Bitmap.createBitmap(3000, 2000, Bitmap.Config.ARGB_8888)

        val smallMosaic = rasterizer.pixelate(small)
        val largeMosaic = rasterizer.pixelate(large)

        assertEquals(smallMosaic.width to smallMosaic.height, largeMosaic.width to largeMosaic.height)
        assertEquals(StrokeGeometry.PIXELATE_BLOCKS, smallMosaic.width)
        listOf(small, large, smallMosaic, largeMosaic).forEach(Bitmap::recycle)
    }

    // ── The header probe ──────────────────────────────────────────────────────

    @Test
    fun `probing a source reports its own dimensions, not a capped decode`() = runTest {
        val probe = requireNotNull(rasterizer.probeSource(sourceImage(1234, 567, "probe.jpg")))

        assertEquals(1234, probe.width)
        assertEquals(567, probe.height)
        assertTrue("a real file has a size", probe.bytes > 0)
    }

    @Test
    fun `probing an unreadable source reports nothing rather than zeroes`() = runTest {
        assertNull(rasterizer.probeSource(Uri.parse("file:///nope/missing.jpg")))
    }

    // ── Overlays ─────────────────────────────────────────────────────────────

    @Test
    fun `a placed shape lands where it was placed and leaves the rest of the photo alone`() = runTest {
        val source = flatSource(400, 400, Color.WHITE, "overlay-shape.png")

        val output = decode(
            rasterizer.rasterize(
                source,
                listOf(
                    RasterOp.Overlays(
                        listOf(
                            ImageOverlay(
                                content = OverlayContent.Shape(ShapeKind.RECTANGLE, 0xFFFF0000, filled = true),
                                centerX = 0.5f,
                                centerY = 0.5f,
                            ),
                        ),
                    ),
                ),
                emptySet(),
            ),
        )

        // A filled rectangle centred on the photo: red in the middle, and the
        // corners untouched. The point is the *centre* — everything about where
        // an overlay lands comes from `OverlayGeometry`, and this is what proves
        // the flatten reads the same normalized coordinates the editor writes.
        assertRed(output.getPixel(200, 200))
        assertWhite("the corner", output.getPixel(5, 5))
        assertWhite("the corner", output.getPixel(395, 395))
        output.recycle()
    }

    @Test
    fun `a text run wider than the photo wraps onto more lines instead of running off both edges`() = runTest {
        val source = flatSource(400, 400, Color.WHITE, "overlay-text-wrap.png")

        val output = decode(
            rasterizer.rasterize(
                source,
                listOf(
                    RasterOp.Overlays(
                        listOf(
                            ImageOverlay(
                                content = OverlayContent.Text(
                                    text = "a caption long enough that one line cannot hold it",
                                    colorArgb = 0xFFFF0000,
                                    filled = true,
                                ),
                                centerX = 0.5f,
                                centerY = 0.5f,
                            ),
                        ),
                    ),
                ),
                emptySet(),
            ),
        )

        // At the base size the font is 22% of the long edge — 88 px here — so a
        // single line of this caption is several photos wide and would be cut
        // off at both edges of the file. Wrapped at the photo's width it stacks
        // into lines, and the stack is tall enough to leave ink in the top
        // quarter, where a single centred line (spanning roughly y ∈ 150..250)
        // never reaches.
        fun inkIn(top: Int, bottom: Int): Boolean = (top until bottom).any { y ->
            (0 until output.width).any { x -> output.getPixel(x, y) != Color.WHITE }
        }
        assertTrue("wrapped text should reach the top quarter", inkIn(0, 100))
        assertTrue("wrapped text should reach the bottom quarter", inkIn(300, 400))
        output.recycle()
    }

    @Test
    fun `an overlay repaints pixels without changing the photo's dimensions`() = runTest {
        val source = flatSource(320, 240, Color.WHITE, "overlay-dimensions.png")

        val output = rasterizer.rasterize(
            source,
            listOf(
                RasterOp.Overlays(
                    listOf(ImageOverlay(OverlayContent.Sticker("heart"), 0.5f, 0.5f)),
                ),
            ),
            emptySet(),
        )

        assertEquals(320 to 240, dimensionsOf(output))
    }

    @Test
    fun `a sticker id nothing answers to loses that sticker rather than the whole flatten`() = runTest {
        val source = flatSource(200, 200, Color.WHITE, "overlay-unknown.png")

        val output = decode(
            rasterizer.rasterize(
                source,
                listOf(
                    RasterOp.Overlays(
                        listOf(ImageOverlay(OverlayContent.Sticker("not-in-any-pack"), 0.5f, 0.5f)),
                    ),
                ),
                emptySet(),
            ),
        )

        // A pack that shrinks under a saved placement must not take the send
        // with it: the unknown id draws nothing and the photo comes out whole.
        assertWhite("an unresolvable sticker draws nothing", output.getPixel(100, 100))
        output.recycle()
    }

    @Test
    fun `stacking overlays paints the last one on top`() = runTest {
        val source = flatSource(400, 400, Color.WHITE, "overlay-z.png")

        val output = decode(
            rasterizer.rasterize(
                source,
                listOf(
                    RasterOp.Overlays(
                        listOf(
                            ImageOverlay(
                                OverlayContent.Shape(ShapeKind.RECTANGLE, 0xFF00FF00, filled = true),
                                0.5f,
                                0.5f,
                                scale = 2f,
                            ),
                            ImageOverlay(
                                OverlayContent.Shape(ShapeKind.RECTANGLE, 0xFFFF0000, filled = true),
                                0.5f,
                                0.5f,
                            ),
                        ),
                    ),
                ),
                emptySet(),
            ),
        )

        // List order is z-order: the last thing placed is the thing on top,
        // which is the only rule anyone would predict from dragging objects.
        assertRed(output.getPixel(200, 200))
        output.recycle()
    }
}
