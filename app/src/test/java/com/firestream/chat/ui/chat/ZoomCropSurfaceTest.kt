package com.firestream.chat.ui.chat

import android.graphics.drawable.ShapeDrawable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.IntSize
import com.firestream.chat.ui.chat.imageedit.CropAspect
import com.firestream.chat.ui.chat.imageedit.CropRect
import com.firestream.chat.ui.chat.imageedit.PendingCrop
import com.firestream.chat.ui.chat.imageedit.ScreenRect
import com.firestream.chat.ui.chat.imageedit.ViewportGeometry
import com.firestream.chat.ui.chat.imageedit.ZoomTransform
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * When the zoom on screen is worked out again, and what that may write back.
 *
 * `ViewportGeometryTest` pins the arithmetic. This test pins the wiring around
 * it: a resize and a new shape from the crop pill both carry the user's zoom
 * into the box, and neither one writes the crop.
 *
 * A 3000 × 4000 photo in a 300 × 460 box fits to 300 × 400 (0.1 screen px per
 * photo px). The keyboard takes the box down to 300 × 320, where the photo fits
 * to 240 × 320 (0.08). The crop starts as the middle half of the photo each
 * way, restored at 2x, with the pill on 1:1: a 300 × 300 square on screen.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [31], application = android.app.Application::class)
class ZoomCropSurfaceTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    private val boxWidth = 300
    private val tallHeight = 460
    private val keyboardHeight = 320
    private val photoWidth = 3000
    private val photoHeight = 4000
    private val middleHalf = CropRect(0.25f, 0.25f, 0.75f, 0.75f)

    private var boxHeight by mutableIntStateOf(tallHeight)
    private var crop by mutableStateOf(
        PendingCrop(viewport = middleHalf, aspect = CropAspect.SQUARE, imageWidth = photoWidth, imageHeight = photoHeight)
    )
    private var isActive by mutableStateOf(true)
    private var measured = IntSize.Zero
    private var writes = 0
    private val zoom = ZoomableState()

    private fun setContent(resetWhenInactive: Boolean = false, restoredScale: Float = 2f) {
        val photo = ShapeDrawable().apply {
            intrinsicWidth = photoWidth
            intrinsicHeight = photoHeight
        }
        composeTestRule.setContent {
            val density = LocalDensity.current
            Box(
                modifier = Modifier
                    .requiredSize(with(density) { boxWidth.toDp() }, with(density) { boxHeight.toDp() })
                    .onSizeChanged { measured = it }
            ) {
                ZoomCropSurface(
                    crop = crop,
                    onCropChange = {
                        writes++
                        crop = it
                    },
                    isActive = isActive,
                    resetWhenInactive = resetWhenInactive,
                    zoom = zoom,
                ) { _, onDecoded ->
                    LaunchedEffect(Unit) { onDecoded(photo) }
                }
            }
        }
        composeTestRule.waitForIdle()
        assertEquals("the restore", restoredScale, zoom.transform.scale, 0.001f)
    }

    private fun resizeTo(height: Int) {
        boxHeight = height
        composeTestRule.waitForIdle()
        assertEquals("the box the surface measured", IntSize(boxWidth, height), measured)
    }

    private fun pickNextShape() {
        crop = crop.cycleAspect()
        composeTestRule.waitForIdle()
    }

    private fun onScreen(frame: CropRect): ScreenRect = ViewportGeometry.toScreen(
        frame, zoom.transform, boxWidth.toFloat(), boxHeight.toFloat(), photoWidth, photoHeight,
    )!!

    private fun assertRect(left: Float, top: Float, right: Float, bottom: Float, actual: CropRect) {
        assertEquals("left", left, actual.left, 0.001f)
        assertEquals("top", top, actual.top, 0.001f)
        assertEquals("right", right, actual.right, 0.001f)
        assertEquals("bottom", bottom, actual.bottom, 0.001f)
    }

    private fun assertTransform(expected: ZoomTransform) {
        assertEquals("scale", expected.scale, zoom.transform.scale, 0.001f)
        assertEquals("offsetX", expected.offsetX, zoom.transform.offsetX, 0.5f)
        assertEquals("offsetY", expected.offsetY, zoom.transform.offsetY, 0.5f)
    }

    @Test
    fun `a shape picked with the keyboard up is drawn whole above it`() {
        // With the keyboard up the square keeps its 300 × 300 at 2 × 0.1 / 0.08
        // = 2.5x. The pill's next shape is 4:5, the same width and a quarter
        // taller: 300 × 375 at 2.5x, which would lose 27.5 px off the top and
        // the bottom of a 320 px box. It has to zoom out to 2.133x instead,
        // where it is 256 × 320: the whole height of the box, and centred.
        setContent()
        resizeTo(keyboardHeight)

        pickNextShape()

        assertEquals(CropAspect.PORTRAIT, crop.aspect)
        val rect = onScreen(crop.frame)
        assertEquals("top", 0f, rect.top, 0.5f)
        assertEquals("bottom", keyboardHeight.toFloat(), rect.bottom, 0.5f)
        assertEquals("left", 22f, rect.left, 0.5f)
        assertEquals("right", 278f, rect.right, 0.5f)
    }

    @Test
    fun `a shape picked with the keyboard up does not move the crop that is sent`() {
        // Zooming out to fit the frame is a view of the crop, not a new crop:
        // the viewport is still the middle half the user framed.
        setContent()
        resizeTo(keyboardHeight)
        val before = writes

        pickNextShape()

        assertEquals(before, writes)
        assertEquals(middleHalf, crop.viewport)
        assertRect(0.25f, 0.265625f, 0.75f, 0.734375f, crop.frame)
    }

    @Test
    fun `the keyboard closing after a new shape puts back the zoom the user made`() {
        setContent()
        resizeTo(keyboardHeight)
        pickNextShape()

        resizeTo(tallHeight)

        assertTransform(ZoomTransform(2f, 0f, 0f))
    }

    @Test
    fun `a shape picked with the keyboard down leaves the zoom alone`() {
        // In the box the zoom was made in, every shape cut from the viewport
        // already fits on screen.
        setContent()

        pickNextShape()

        assertTransform(ZoomTransform(2f, 0f, 0f))
    }

    @Test
    fun `a crop handed over from another photo is not written back`() {
        // No host hands over another photo's crop today, so this guard is
        // defensive. If one handed a page at 1x such a crop, a write back, the
        // size stamped or the zoom read as a gesture, would overwrite the
        // host's crop with this photo's size or zoom.
        crop = PendingCrop(imageWidth = photoWidth, imageHeight = photoHeight)
        setContent(restoredScale = 1f)
        val before = writes

        crop = PendingCrop(
            viewport = CropRect(0.1f, 0.1f, 0.4f, 0.4f),
            aspect = CropAspect.WIDE,
            imageWidth = 4000,
            imageHeight = 3000,
        )
        composeTestRule.waitForIdle()

        assertEquals(before, writes)
        assertTransform(ZoomTransform.Identity)
    }

    @Test
    fun `a page paged away while its crop is cleared forgets the zoom`() {
        // The viewer's pager hands a page that scrolls away a crop with no
        // shape, in the same frame that resets its zoom. If the cleared shape
        // carried the zoom first, the reset would change nothing the surface
        // could see.
        // The surface would then keep 2x as the zoom to carry, and the next
        // resize would zoom a photo at 1x back in on its own.
        setContent(resetWhenInactive = true)

        isActive = false
        crop = PendingCrop.None
        composeTestRule.waitForIdle()
        assertTransform(ZoomTransform.Identity)

        isActive = true
        crop = PendingCrop(aspect = CropAspect.SQUARE, imageWidth = photoWidth, imageHeight = photoHeight)
        composeTestRule.waitForIdle()
        resizeTo(keyboardHeight)

        assertTransform(ZoomTransform.Identity)
    }
}
