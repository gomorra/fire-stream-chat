package com.firestream.chat.ui.chat

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.firestream.chat.domain.model.Sticker
import com.firestream.chat.domain.model.StickerPack
import com.firestream.chat.domain.util.StickerSearch
import com.firestream.chat.ui.chat.picker.EmojiSearchStrip
import com.firestream.chat.ui.chat.picker.EmojiTab
import com.firestream.chat.ui.chat.picker.PickerPanel
import com.firestream.chat.ui.chat.picker.PickerPanelState
import com.firestream.chat.ui.chat.picker.PickerSelection
import com.firestream.chat.ui.chat.picker.PickerTab
import com.firestream.chat.ui.chat.picker.StickerLibraryTab
import com.firestream.chat.ui.chat.picker.rememberPickerPanelState
import com.firestream.chat.ui.stickers.StickerCell

/** What the composer's picker hands back. One bundle, to stay under the parameter ceiling (docs/GOTCHAS.md). */
@Immutable
internal data class ComposerPickerCallbacks(
    val onEmoji: (emoji: String, size: Float) -> Unit,
    val onBackspace: () -> Unit,
    val onRecentEmojiUsed: (String) -> Unit,
    val onSticker: (PickerSelection.Sticker) -> Unit,
    val onToggleStickerFavourite: (stickerId: String) -> Unit,
    val onImportStickers: () -> Unit,
    val onCreateSticker: () -> Unit,
)

/**
 * The picker the composer mounts in the keyboard's place: an island with the
 * emoji grid and the sticker library.
 *
 * The reaction sheet and the caption bar stay on [EmojiHandlerPanel]. A
 * reaction and a caption are text, and a sticker is a message of its own.
 *
 * The backspace key is on both tabs. It edits the composer, which is on screen
 * above either of them, and a key that came and went would shift the island.
 *
 * Opening the search focuses its field, so the keyboard comes up at once.
 * Where the panel goes then is the host's call, made from [state] by
 * [composerSearchLayout]. When the host asks for [compact], the emoji tab
 * becomes a single row of results above the search row.
 */
@Composable
internal fun ComposerPickerPanel(
    recentEmojis: List<String>,
    stickerPacks: List<StickerPack>,
    recentStickers: List<Sticker>,
    callbacks: ComposerPickerCallbacks,
    modifier: Modifier = Modifier,
    state: PickerPanelState = rememberPickerPanelState(COMPOSER_PICKER_TABS),
    compact: Boolean = false,
) {
    PickerPanel(
        tabs = COMPOSER_PICKER_TABS,
        modifier = modifier,
        state = state,
        compact = compact,
        focusSearchOnOpen = true,
        searchTrailing = { PickerBackspaceKey(onClick = callbacks.onBackspace) },
    ) { tab, query ->
        when (tab) {
            PickerTab.STICKER_LIBRARY -> StickerLibraryTab(
                query = query,
                packs = stickerPacks,
                recents = recentStickers,
                onSelection = callbacks.onSticker,
                onToggleFavourite = callbacks.onToggleStickerFavourite,
                onImport = callbacks.onImportStickers,
                onCreate = callbacks.onCreateSticker,
            )

            PickerTab.EMOJI -> {
                val onSelection: (PickerSelection.Emoji) -> Unit = { pick ->
                    callbacks.onEmoji(pick.emoji, pick.size)
                    callbacks.onRecentEmojiUsed(pick.emoji)
                }
                if (compact) {
                    EmojiSearchStrip(query = query, recentEmojis = recentEmojis, onSelection = onSelection)
                } else {
                    EmojiTab(query = query, recentEmojis = recentEmojis, onSelection = onSelection)
                }
            }

            // Not declared by the composer. Named rather than swept into an
            // `else`, so a tab added to the enum fails to compile here.
            PickerTab.STICKER, PickerTab.GIF, PickerTab.TEXT, PickerTab.SHAPE -> Unit
        }
    }
}

internal val COMPOSER_PICKER_TABS = listOf(PickerTab.EMOJI, PickerTab.STICKER_LIBRARY)

/** Where the composer puts its picker. */
internal enum class ComposerSearchLayout {
    /** In the keyboard's place, under the composer. No search runs, or the keyboard is down. */
    PANEL,

    /** One row of results and the search row, directly on top of the keyboard. The composer stays in view. */
    STRIP,

    /** Over the conversation and the composer, from the top bar down to the keyboard. */
    FULL,
}

/**
 * Where the composer puts its picker while [searchingTab] is being searched.
 *
 * - **Emoji search is a strip** while the keyboard is up. You often pick
 *   several emoji in a row and want to see them land in the message, and a
 *   few letters leave few enough results for one row. With the keyboard down
 *   the panel is back in its place and the results are the full grid.
 * - **Sticker search fills the screen.** Stickers are large, so they need the
 *   room, and each pick is sent at once, so the composer is not needed.
 *   GIF search will work the same way once the composer offers it.
 */
internal fun composerSearchLayout(searchingTab: PickerTab?, imeVisible: Boolean): ComposerSearchLayout =
    when (searchingTab) {
        PickerTab.EMOJI -> if (imeVisible) ComposerSearchLayout.STRIP else ComposerSearchLayout.PANEL
        PickerTab.STICKER_LIBRARY, PickerTab.GIF -> ComposerSearchLayout.FULL
        PickerTab.STICKER, PickerTab.TEXT, PickerTab.SHAPE, null -> ComposerSearchLayout.PANEL
    }

/** How many stickers the strip offers. More than a few screens of them is a search, which the tab does. */
private const val MAX_SUGGESTIONS = 24

/**
 * The strip above the composer that offers the stickers tagged with the one
 * emoji typed into it. It shows only while [text] is exactly one emoji that
 * tags a sticker in [packs].
 */
@Composable
internal fun StickerSuggestionStrip(
    text: String,
    packs: List<StickerPack>,
    onSelection: (PickerSelection.Sticker) -> Unit,
    modifier: Modifier = Modifier,
) {
    // The composer's text changes on every keystroke. The library is searched
    // only when the emoji it amounts to changes.
    val emoji = StickerSearch.suggestionKey(text)
    val matches = remember(emoji, packs) {
        if (emoji == null) emptyList() else StickerSearch.byEmojis(packs, listOf(emoji)).take(MAX_SUGGESTIONS)
    }
    AnimatedVisibility(
        visible = matches.isNotEmpty(),
        enter = slideInVertically { it } + fadeIn(),
        exit = slideOutVertically { it } + fadeOut(),
        modifier = modifier,
    ) {
        LazyRow(
            contentPadding = PaddingValues(horizontal = 8.dp),
            modifier = Modifier
                .fillMaxWidth()
                .background(MaterialTheme.colorScheme.surface)
                .semantics { contentDescription = "Sticker suggestions" },
        ) {
            items(matches, key = { it.sticker.id }) { match ->
                StickerCell(
                    sticker = match.sticker,
                    isSelected = false,
                    onClick = { onSelection(PickerSelection.Sticker(it, match.packId)) },
                    modifier = Modifier.size(72.dp),
                )
            }
        }
    }
}
