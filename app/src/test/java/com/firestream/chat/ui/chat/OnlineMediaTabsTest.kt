package com.firestream.chat.ui.chat

import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.unit.dp
import com.firestream.chat.domain.model.OnlineMedia
import com.firestream.chat.domain.model.OnlineMediaKind
import com.firestream.chat.domain.model.OnlineMediaRendition
import com.firestream.chat.domain.model.StickerPack
import com.firestream.chat.domain.model.StickerPackKind
import com.firestream.chat.test.fakes.testSticker
import com.firestream.chat.test.fakes.testStickerPack
import com.firestream.chat.ui.chat.gif.OnlineMediaFeed
import com.firestream.chat.ui.chat.gif.OnlineMediaUiState
import com.firestream.chat.ui.chat.picker.OnlineMediaCallbacks
import com.firestream.chat.ui.chat.picker.PickerSelection
import com.firestream.chat.ui.chat.picker.onlineMediaTag
import com.firestream.chat.ui.stickers.stickerCellTag
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** The online parts of the composer's picker: the GIFs tab, and KLIPY's stickers in the Stickers tab. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [31], application = android.app.Application::class)
class OnlineMediaTabsTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    private val queries = mutableListOf<Pair<OnlineMediaKind, String>>()
    private val picks = mutableListOf<OnlineMedia>()
    private val stickerPicks = mutableListOf<PickerSelection.Sticker>()
    private var accepts = 0
    private var retries = 0

    private fun media(slug: String, kind: OnlineMediaKind): OnlineMedia {
        // A path with no file: the test must not ask KLIPY for a preview.
        val file = OnlineMediaRendition("/nowhere/$slug.webp", 100, 80, "image/webp")
        return OnlineMedia(kind, slug, slug, file, file)
    }

    private fun feed(kind: OnlineMediaKind, vararg slugs: String) =
        OnlineMediaFeed(items = slugs.map { media(it, kind) }, page = 1)

    private var online by mutableStateOf(OnlineMediaUiState())

    private fun setPanel(state: OnlineMediaUiState, packs: List<StickerPack> = emptyList()) {
        online = state
        composeTestRule.setContent {
            MaterialTheme {
                ComposerPickerPanel(
                    recentEmojis = listOf("😀"),
                    stickerPacks = packs,
                    recentStickers = emptyList(),
                    callbacks = ComposerPickerCallbacks({ _, _ -> }, {}, {}, { stickerPicks += it }, {}, {}, {}),
                    modifier = Modifier.height(420.dp),
                    online = online,
                    onlineCallbacks = OnlineMediaCallbacks(
                        onQuery = { kind, query -> queries += kind to query },
                        onRetry = { retries++ },
                        onAcceptNotice = { accepts++ },
                        onPick = { picks += it },
                    ),
                )
            }
        }
    }

    private fun open(tab: String) {
        composeTestRule.onNodeWithContentDescription(tab).performClick()
        composeTestRule.waitForIdle()
    }

    @Test
    fun `a build without a key has no GIFs tab and no Online shelf`() {
        setPanel(OnlineMediaUiState(isAvailable = false), packs = listOf(testStickerPack("animals", StickerPackKind.USER, testSticker("cat", "😺"))))

        composeTestRule.onNodeWithContentDescription("GIFs").assertDoesNotExist()
        open("Stickers")
        composeTestRule.onNodeWithContentDescription("Online").assertDoesNotExist()
        assertTrue(queries.isEmpty())
    }

    @Test
    fun `the GIFs tab searches under KLIPY's name and carries its mark`() {
        setPanel(OnlineMediaUiState(isAvailable = true, noticeAccepted = true, gifs = feed(OnlineMediaKind.GIF, "wave")))
        open("GIFs")

        composeTestRule.onNodeWithText("Powered by KLIPY").assertIsDisplayed()
        composeTestRule.onNodeWithContentDescription("Search").performClick()
        composeTestRule.onNodeWithText("Search KLIPY").assertIsDisplayed()

        composeTestRule.onNodeWithContentDescription("Search field").performTextInput("cat")
        composeTestRule.waitForIdle()

        assertEquals(listOf(OnlineMediaKind.GIF to "", OnlineMediaKind.GIF to "cat"), queries)
    }

    @Test
    fun `the notice comes before the first request, and accepting it shows the grid`() {
        setPanel(OnlineMediaUiState(isAvailable = true, noticeAccepted = false))
        open("GIFs")

        composeTestRule.onNodeWithText("GIFs and online stickers come from KLIPY").assertIsDisplayed()
        composeTestRule.onNodeWithText("Continue").performClick()
        assertEquals(1, accepts)

        online = OnlineMediaUiState(isAvailable = true, noticeAccepted = true, gifs = feed(OnlineMediaKind.GIF, "wave"))
        composeTestRule.waitForIdle()

        composeTestRule.onNodeWithText("Continue").assertDoesNotExist()
        composeTestRule.onNodeWithTag(onlineMediaTag("wave")).performClick()
        assertEquals(listOf("wave"), picks.map { it.slug })
    }

    @Test
    fun `a failed load says why and offers a retry`() {
        setPanel(
            OnlineMediaUiState(
                isAvailable = true,
                noticeAccepted = true,
                gifs = OnlineMediaFeed(error = com.firestream.chat.domain.model.AppError.Validation("KLIPY is busy")),
            ),
        )
        open("GIFs")

        composeTestRule.onNodeWithText("KLIPY is busy").assertIsDisplayed()
        composeTestRule.onNodeWithText("Try again").performClick()

        assertEquals(1, retries)
    }

    @Test
    fun `the Online shelf shows KLIPY's stickers apart from the library, and a tap sends one`() {
        setPanel(
            OnlineMediaUiState(isAvailable = true, noticeAccepted = true, stickers = feed(OnlineMediaKind.STICKER, "hello")),
            packs = listOf(testStickerPack("animals", StickerPackKind.USER, testSticker("cat", "😺"))),
        )
        open("Stickers")
        // The library's shelf is the one the tab opens on, and nothing is asked of KLIPY for it.
        composeTestRule.onNodeWithTag(stickerCellTag("cat")).assertIsDisplayed()
        assertTrue(queries.isEmpty())

        open("Online")

        composeTestRule.onNodeWithTag(stickerCellTag("cat")).assertDoesNotExist()
        composeTestRule.onNodeWithText("Powered by KLIPY").assertIsDisplayed()
        composeTestRule.onNodeWithTag(onlineMediaTag("hello")).performClick()
        assertEquals(listOf(OnlineMediaKind.STICKER to ""), queries)
        assertEquals(listOf("hello"), picks.map { it.slug })
        assertTrue(stickerPicks.isEmpty())
    }

    @Test
    fun `an empty library still offers the import beside the Online shelf`() {
        setPanel(OnlineMediaUiState(isAvailable = true, noticeAccepted = true))
        open("Stickers")

        composeTestRule.onNodeWithText("Import stickers").assertIsDisplayed()
        composeTestRule.onNodeWithContentDescription("Online").assertIsDisplayed()
    }

    @Test
    fun `a search of the library shows KLIPY's results in a section of their own`() {
        setPanel(
            OnlineMediaUiState(isAvailable = true, noticeAccepted = true, stickers = feed(OnlineMediaKind.STICKER, "hello")),
            packs = listOf(testStickerPack("animals", StickerPackKind.USER, testSticker("cat", "😺"))),
        )
        open("Stickers")
        composeTestRule.onNodeWithContentDescription("Search").performClick()
        composeTestRule.onNodeWithContentDescription("Search field").performTextInput("😺")
        composeTestRule.waitForIdle()

        composeTestRule.onNodeWithText("Results").assertIsDisplayed()
        composeTestRule.onNodeWithTag(stickerCellTag("cat")).assertIsDisplayed()
        composeTestRule.onNodeWithText("More online").assertIsDisplayed()
        composeTestRule.onNodeWithTag(onlineMediaTag("hello")).assertIsDisplayed()
        assertEquals(listOf(OnlineMediaKind.STICKER to "😺"), queries)
    }
}
