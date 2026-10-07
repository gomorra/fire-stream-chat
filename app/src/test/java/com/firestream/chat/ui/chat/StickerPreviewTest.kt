package com.firestream.chat.ui.chat

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assertHeightIsEqualTo
import androidx.compose.ui.test.assertWidthIsEqualTo
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import com.firestream.chat.domain.model.StickerFormat
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** The sticker at the top of the sticker sheet, and its tap to enlarge. */
@RunWith(RobolectricTestRunner::class)
// Stub Application to bypass FireStreamApp's Hilt + Firebase init.
@Config(sdk = [31], application = android.app.Application::class, qualifiers = "w400dp-h800dp")
class StickerPreviewTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    private fun setPreview() {
        composeTestRule.setContent {
            MaterialTheme { StickerPreview(model = "/nowhere/cat.webp", format = StickerFormat.WEBP) }
        }
    }

    @Test
    fun `the preview is a little larger than the sticker in the bubble`() {
        assertTrue(STICKER_PREVIEW_SIZE > STICKER_BUBBLE_SIZE)
        assertTrue(STICKER_PREVIEW_SIZE < STICKER_BUBBLE_SIZE * 1.5f)
        setPreview()

        composeTestRule.onNodeWithTag(STICKER_PREVIEW_TAG)
            .assertWidthIsEqualTo(STICKER_PREVIEW_SIZE)
            .assertHeightIsEqualTo(STICKER_PREVIEW_SIZE)
    }

    @Test
    fun `a tap enlarges the sticker to most of the width, and a second tap shrinks it back`() {
        setPreview()
        val node = composeTestRule.onNodeWithTag(STICKER_PREVIEW_TAG)

        node.performClick()
        composeTestRule.waitForIdle()
        // 80% of the 400 dp screen, short of the full width.
        node.assertWidthIsEqualTo(320.dp).assertHeightIsEqualTo(320.dp)

        node.performClick()
        composeTestRule.waitForIdle()
        node.assertWidthIsEqualTo(STICKER_PREVIEW_SIZE)
    }
}
