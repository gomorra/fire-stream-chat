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
import com.firestream.chat.ui.chat.picker.EmojiTab
import com.firestream.chat.ui.chat.picker.PickerPanel
import com.firestream.chat.ui.chat.picker.PickerSelection
import com.firestream.chat.ui.chat.picker.PickerTab
import com.firestream.chat.ui.chat.picker.StickerLibraryTab
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
 */
@Composable
internal fun ComposerPickerPanel(
    recentEmojis: List<String>,
    stickerPacks: List<StickerPack>,
    recentStickers: List<Sticker>,
    callbacks: ComposerPickerCallbacks,
    modifier: Modifier = Modifier,
) {
    PickerPanel(
        tabs = COMPOSER_TABS,
        modifier = modifier,
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

            PickerTab.EMOJI -> EmojiTab(
                query = query,
                recentEmojis = recentEmojis,
                onSelection = { pick ->
                    callbacks.onEmoji(pick.emoji, pick.size)
                    callbacks.onRecentEmojiUsed(pick.emoji)
                },
            )

            // Not declared by the composer. Named rather than swept into an
            // `else`, so a tab added to the enum fails to compile here.
            PickerTab.STICKER, PickerTab.GIF, PickerTab.TEXT, PickerTab.SHAPE -> Unit
        }
    }
}

private val COMPOSER_TABS = listOf(PickerTab.EMOJI, PickerTab.STICKER_LIBRARY)

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
