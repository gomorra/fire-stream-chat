package com.firestream.chat.ui.chat

import android.net.Uri
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performClick
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The preview's history controls, driven through the screen rather than through
 * [PendingMedia] directly — what the send button hands back is the only thing
 * that decides which bytes leave the device.
 *
 * The pill is hidden until an item has history, so every test here seeds one.
 * The URIs are stand-ins for files the rasterizer would have written; what is
 * under test is the cursor arithmetic and the fallback that runs when one of
 * those files is no longer on disk.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29], application = android.app.Application::class)
class ImagePreviewScreenHistoryTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    private fun edited(steps: Int, cursor: Int = steps) = PendingMedia(
        originalUri = Uri.parse("content://pick/1"),
        mimeType = "image/jpeg",
        editHistory = (1..steps).map { "file:///edits/step$it.jpg" },
        editCursor = cursor,
    )

    private fun setContent(
        item: PendingMedia,
        editStepExists: (Uri) -> Boolean = { true },
        onDiscardEditSteps: (List<String>) -> Unit = {},
        onDismiss: () -> Unit = {},
        onSend: (List<PendingMedia>) -> Unit,
    ) {
        composeTestRule.setContent {
            MaterialTheme {
                ImagePreviewScreen(
                    items = listOf(item),
                    recentEmojis = emptyList(),
                    defaultIsHd = false,
                    onEmojiUsed = {},
                    onSend = onSend,
                    onDownload = {},
                    onDismiss = onDismiss,
                    editStepExists = editStepExists,
                    onDiscardEditSteps = onDiscardEditSteps,
                )
            }
        }
    }

    private fun sentUri(sent: List<PendingMedia>?) = sent?.single()?.uri?.toString()

    @Test
    fun `the history pill is hidden for a pick with no edits`() {
        setContent(PendingMedia(Uri.parse("content://pick/1"), "image/jpeg")) {}

        // Hidden, not disabled: a fresh pick should look untouched.
        composeTestRule.onNodeWithContentDescription("Undo edit").assertDoesNotExist()
        composeTestRule.onNodeWithContentDescription("Show original image").assertDoesNotExist()
    }

    @Test
    fun `the history pill appears once an item has a step to walk back to`() {
        setContent(edited(steps = 2)) {}

        composeTestRule.onNodeWithContentDescription("Undo edit").assertIsDisplayed()
        composeTestRule.onNodeWithContentDescription("Show original image").assertIsDisplayed()
    }

    @Test
    fun `showing the original sends the untouched pick`() {
        var sent: List<PendingMedia>? = null
        setContent(edited(steps = 3), onSend = { sent = it })

        composeTestRule.onNodeWithContentDescription("Show original image").performClick()
        composeTestRule.onNodeWithContentDescription("Send").performClick()

        assertEquals("content://pick/1", sentUri(sent))
    }

    @Test
    fun `toggling back from the original returns to the step the user was on`() {
        // The regression this test exists for: "on" used to jump to the top of
        // the history, so peeking at the original from step 1 of 3 came back
        // having silently re-applied steps 2 and 3.
        var sent: List<PendingMedia>? = null
        setContent(edited(steps = 3), onSend = { sent = it })

        composeTestRule.onNodeWithContentDescription("Undo edit").performClick()
        composeTestRule.onNodeWithContentDescription("Undo edit").performClick()
        composeTestRule.onNodeWithContentDescription("Show original image").performClick()
        composeTestRule.onNodeWithContentDescription("Show edited image").performClick()
        composeTestRule.onNodeWithContentDescription("Send").performClick()

        assertEquals("file:///edits/step1.jpg", sentUri(sent))
    }

    @Test
    fun `the first peek comes back to the newest step`() {
        var sent: List<PendingMedia>? = null
        setContent(edited(steps = 3), onSend = { sent = it })

        composeTestRule.onNodeWithContentDescription("Show original image").performClick()
        composeTestRule.onNodeWithContentDescription("Show edited image").performClick()
        composeTestRule.onNodeWithContentDescription("Send").performClick()

        assertEquals("file:///edits/step3.jpg", sentUri(sent))
    }

    @Test
    fun `undo walks back one step at a time and stops at the original`() {
        var sent: List<PendingMedia>? = null
        setContent(edited(steps = 2), onSend = { sent = it })

        composeTestRule.onNodeWithContentDescription("Undo edit").performClick()
        composeTestRule.onNodeWithContentDescription("Undo edit").performClick()
        // At zero the button disables itself rather than walking off the end.
        composeTestRule.onNodeWithContentDescription("Undo edit").assertIsNotEnabled()
        composeTestRule.onNodeWithContentDescription("Send").performClick()

        assertEquals("content://pick/1", sentUri(sent))
    }

    @Test
    fun `redo walks forward again and stops at the newest step`() {
        var sent: List<PendingMedia>? = null
        setContent(edited(steps = 2), onSend = { sent = it })

        composeTestRule.onNodeWithContentDescription("Undo edit").performClick()
        composeTestRule.onNodeWithContentDescription("Redo edit").performClick()
        composeTestRule.onNodeWithContentDescription("Redo edit").assertIsNotEnabled()
        composeTestRule.onNodeWithContentDescription("Send").performClick()

        assertEquals("file:///edits/step2.jpg", sentUri(sent))
    }

    @Test
    fun `an undo after a peek retires the remembered step`() {
        // The peek's return position is only meaningful until the user moves the
        // cursor deliberately; after that it would snap them somewhere they left.
        var sent: List<PendingMedia>? = null
        setContent(edited(steps = 3), onSend = { sent = it })

        composeTestRule.onNodeWithContentDescription("Undo edit").performClick()  // → step 2
        composeTestRule.onNodeWithContentDescription("Show original image").performClick()
        composeTestRule.onNodeWithContentDescription("Redo edit").performClick()  // → step 1
        composeTestRule.onNodeWithContentDescription("Show original image").performClick()
        composeTestRule.onNodeWithContentDescription("Show edited image").performClick()
        composeTestRule.onNodeWithContentDescription("Send").performClick()

        assertEquals("file:///edits/step1.jpg", sentUri(sent))
    }

    // ── the history is a list of files the OS may delete ──────────────────────

    @Test
    fun `a step whose file has vanished is skipped rather than sent`() {
        // cacheDir can be reclaimed under storage pressure at any moment, and
        // the rasterizer's byte budget evicts deliberately. Sending a URI that
        // resolves to nothing is the one outcome that is not acceptable.
        var sent: List<PendingMedia>? = null
        setContent(
            edited(steps = 3),
            editStepExists = { it.toString() != "file:///edits/step3.jpg" },
            onSend = { sent = it },
        )

        composeTestRule.onNodeWithContentDescription("Send").performClick()

        assertEquals("file:///edits/step2.jpg", sentUri(sent))
    }

    @Test
    fun `an entirely evicted history sends the untouched pick`() {
        var sent: List<PendingMedia>? = null
        setContent(edited(steps = 3), editStepExists = { false }, onSend = { sent = it })

        composeTestRule.onNodeWithContentDescription("Send").performClick()

        assertEquals("content://pick/1", sentUri(sent))
    }

    @Test
    fun `the page on screen falls back to a surviving step without being sent`() {
        // The effect that re-resolves the visible page runs on its own, so the
        // history controls agree with what the pager is showing: at step 1 of 3
        // with the top two gone, redo has nothing left to walk forward to.
        setContent(edited(steps = 3), editStepExists = { it.toString() == "file:///edits/step1.jpg" }) {}

        composeTestRule.waitForIdle()

        composeTestRule.onNodeWithContentDescription("Redo edit").assertIsNotEnabled()
    }

    @Test
    fun `dismissing the batch hands back every step it was holding`() {
        // The regression this test exists for: the discard used to read the
        // caller's original pick list, where no edit ever lands, so back always
        // handed back an empty list and leaked the whole session's files.
        var discarded: List<String>? = null
        var dismissed = false
        setContent(
            edited(steps = 3),
            onDiscardEditSteps = { discarded = it },
            onDismiss = { dismissed = true },
        ) {}

        composeTestRule.onNodeWithContentDescription("Back").performClick()

        assertEquals(
            listOf("file:///edits/step1.jpg", "file:///edits/step2.jpg", "file:///edits/step3.jpg"),
            discarded,
        )
        assertTrue(dismissed)
    }
}
