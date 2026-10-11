package com.firestream.chat.ui.stickers

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import com.firestream.chat.domain.model.Message
import com.firestream.chat.domain.model.MessageType
import com.firestream.chat.domain.model.Sticker
import com.firestream.chat.domain.model.StickerAutoName
import com.firestream.chat.domain.model.StickerFormat
import com.firestream.chat.domain.model.StickerPackKind
import com.firestream.chat.test.fakes.testSticker
import com.firestream.chat.test.fakes.testStickerPack
import com.firestream.chat.ui.chat.OverlaysState
import com.firestream.chat.ui.chat.StickerActionsSheet
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** The one sticker sheet: what it shows of a sticker's words, and its rows in a chat and in the manager. */
@RunWith(RobolectricTestRunner::class)
// Stub Application to bypass FireStreamApp's Hilt + Firebase init.
@Config(sdk = [31], application = android.app.Application::class, qualifiers = "w400dp-h1200dp")
class StickerDetailSheetTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    private val found = StickerAutoName(
        nameEn = "Sleepy sloth",
        nameDe = "Müdes Faultier",
        words = listOf("Faultier", "tired"),
        text = "Gute Nacht",
        emojis = listOf("🦥"),
    )

    private fun show(sticker: Sticker?, isFavourite: Boolean = false, callbacks: StickerDetailCallbacks = StickerDetailCallbacks()) {
        composeTestRule.setContent {
            MaterialTheme {
                StickerDetailContent(
                    state = StickerDetailState(
                        model = "/nowhere/cat.webp",
                        format = StickerFormat.WEBP,
                        sticker = sticker,
                        isFavourite = isFavourite,
                    ),
                    callbacks = callbacks,
                )
            }
        }
    }

    @Test
    fun `a sticker shows the words of both groups, each in its own lines`() {
        show(testSticker("cat", "😺", "😴").copy(names = listOf("Cat", "Katze"), autoName = found))

        composeTestRule.onNodeWithTag(STICKER_PREVIEW_TAG).assertIsDisplayed()
        composeTestRule.onNodeWithText(YOUR_WORDS_TITLE).assertIsDisplayed()
        composeTestRule.onNodeWithText("Cat · Katze").assertIsDisplayed()
        composeTestRule.onNodeWithText("😺 😴").assertIsDisplayed()
        composeTestRule.onNodeWithText(AUTOMATIC_WORDS_TITLE).assertIsDisplayed()
        composeTestRule.onNodeWithText("Müdes Faultier · Sleepy sloth").assertIsDisplayed()
        composeTestRule.onNodeWithText("Faultier, tired").assertIsDisplayed()
        composeTestRule.onNodeWithText("Text: Gute Nacht").assertIsDisplayed()
        composeTestRule.onNodeWithText(NO_NAMES_YET).assertDoesNotExist()
    }

    @Test
    fun `a group with nothing in it is left out`() {
        show(testSticker("cat").copy(autoName = found))

        composeTestRule.onNodeWithText(YOUR_WORDS_TITLE).assertDoesNotExist()
        composeTestRule.onNodeWithText(AUTOMATIC_WORDS_TITLE).assertIsDisplayed()
    }

    @Test
    fun `a library sticker with no words at all says so`() {
        show(testSticker("cat"))

        composeTestRule.onNodeWithText(NO_NAMES_YET).assertIsDisplayed()
        composeTestRule.onNodeWithText(YOUR_WORDS_TITLE).assertDoesNotExist()
        composeTestRule.onNodeWithText(AUTOMATIC_WORDS_TITLE).assertDoesNotExist()
    }

    @Test
    fun `a sticker that is not in the library shows its picture and no words`() {
        show(sticker = null, callbacks = StickerDetailCallbacks(onViewPack = {}))

        composeTestRule.onNodeWithTag(STICKER_PREVIEW_TAG).assertIsDisplayed()
        composeTestRule.onNodeWithText(NO_NAMES_YET).assertDoesNotExist()
        composeTestRule.onNodeWithText("Add to favourites").assertIsDisplayed()
        composeTestRule.onNodeWithText("View pack").assertIsDisplayed()
        composeTestRule.onNodeWithText(EDIT_STICKER_LABEL).assertDoesNotExist()
    }

    @Test
    fun `the manager's sheet has the star and the edit, and no View pack`() {
        val done = mutableListOf<String>()
        show(
            testSticker("cat"),
            isFavourite = true,
            callbacks = StickerDetailCallbacks(
                onToggleFavourite = { done += "star" },
                onEdit = { done += "edit" },
                onDismiss = { done += "dismiss" },
            ),
        )

        composeTestRule.onNodeWithText("View pack").assertDoesNotExist()
        composeTestRule.onNodeWithText("Remove from favourites").performScrollTo().performClick()
        composeTestRule.onNodeWithText(EDIT_STICKER_LABEL).performScrollTo().performClick()

        // The star closes the sheet. The edit takes its place, which is its host's to do.
        assertEquals(listOf("star", "dismiss", "edit"), done)
    }

    // --- In a chat ---

    private val message = Message(id = "m1", chatId = "chat1", type = MessageType.STICKER, stickerId = "cat")

    private fun showInChat(
        overlays: OverlaysState,
        onViewPack: (() -> Unit)? = null,
        onSaveWords: (String, List<String>, List<String>, Boolean) -> Unit = { _, _, _, _ -> },
        onDismiss: () -> Unit = {},
    ) {
        composeTestRule.setContent {
            MaterialTheme {
                StickerActionsSheet(
                    message = message,
                    overlays = overlays,
                    onToggleFavourite = {},
                    onViewPack = onViewPack,
                    onSaveWords = onSaveWords,
                    onDismiss = onDismiss,
                )
            }
        }
    }

    @Test
    fun `in a chat the edit takes the sheet's place, and its save hands the words over and closes`() {
        val cat = testSticker("cat", "😺").copy(names = listOf("Cat"))
        val done = mutableListOf<String>()
        showInChat(
            OverlaysState(stickerPacks = listOf(testStickerPack("Cats", StickerPackKind.USER, cat))),
            onSaveWords = { id, emojis, names, stop -> done += "save:$id:$emojis:$names:$stop" },
            onDismiss = { done += "dismiss" },
        )

        // By the click action: a touch misses a sheet that the test's window cuts off.
        composeTestRule.onNodeWithText(EDIT_STICKER_LABEL).performSemanticsAction(SemanticsActions.OnClick)
        composeTestRule.onNodeWithText("Add to favourites").assertDoesNotExist()
        composeTestRule.onNodeWithText("Save").performSemanticsAction(SemanticsActions.OnClick)

        assertEquals(listOf("save:cat:[😺]:[Cat]:false", "dismiss"), done)
    }

    @Test
    fun `in a chat the words come from the library's row, and the star from the favourites`() {
        val cat = testSticker("cat").copy(names = listOf("Cat"))
        showInChat(
            OverlaysState(
                stickerPacks = listOf(testStickerPack("Cats", StickerPackKind.USER, cat)),
                favouriteStickerIds = setOf("cat"),
            ),
            onViewPack = {},
        )

        composeTestRule.onNodeWithText("Cat").assertExists()
        composeTestRule.onNodeWithText("Remove from favourites").assertExists()
        composeTestRule.onNodeWithText("View pack").assertExists()
        composeTestRule.onNodeWithText(EDIT_STICKER_LABEL).assertExists()
    }

    @Test
    fun `in a chat a sticker no pack holds has no words and no edit`() {
        showInChat(OverlaysState(stickerPacks = listOf(testStickerPack("Cats", StickerPackKind.USER, testSticker("other")))))

        composeTestRule.onNodeWithText("Add to favourites").assertExists()
        composeTestRule.onNodeWithText("View pack").assertDoesNotExist()
        composeTestRule.onNodeWithText(NO_NAMES_YET).assertDoesNotExist()
        composeTestRule.onNodeWithText(EDIT_STICKER_LABEL).assertDoesNotExist()
    }
}
