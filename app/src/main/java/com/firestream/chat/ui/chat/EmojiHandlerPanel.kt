package com.firestream.chat.ui.chat

import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Backspace
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.firestream.chat.ui.chat.picker.EmojiQuickReactions
import com.firestream.chat.ui.chat.picker.EmojiTab
import com.firestream.chat.ui.chat.picker.PickerPanel
import com.firestream.chat.ui.chat.picker.PickerSelection
import com.firestream.chat.ui.chat.picker.PickerTab

/** What a host is using the emoji panel for, which is what its chrome differs by. */
enum class EmojiMode {
    /** Types into a text field: the panel offers a backspace key. */
    TEXT_INPUT,

    /** Picks a reaction for a message: the panel offers the quick-reactions strip. */
    REACTION,
}

/**
 * The emoji panel the composer, the reaction sheet and the caption bar mount —
 * a one-tab [PickerPanel] with the emoji grid in it.
 *
 * ### Why this is a thin alias rather than the panel itself
 *
 * The image editor needed a picker with four tabs, and the app already had one
 * picker mounted from three places (`.claude/plans/image-editor.md` §2.8).
 * Adding a fourth copy would have been the third mistake in a row, so the shell
 * became [PickerPanel] and the grid became [EmojiTab]. This alias is what let
 * that extraction land as a commit with **no behaviour change at all**: the
 * three existing call sites still call `EmojiHandlerPanel(mode = …)` and still
 * get exactly the panel they got before, so a regression in the move is
 * bisectable away from the tabs built on top of it.
 *
 * A one-tab host renders no island and no search button — with nothing to
 * collapse for, the field is simply always expanded, which is what these three
 * screens have always shown.
 *
 * ### What belongs to the mode, and why it is passed in rather than looked up
 *
 * The quick-reactions strip and the backspace key are the two things that
 * differ by host, and both are handed to [PickerPanel] as slots rather than
 * being fields it knows about: a shell that knew what a backspace was would
 * grow a second one for every tab that does not want it. The strip is emoji
 * content ([EmojiQuickReactions]) mounted above the search row, and the
 * backspace is a host's own control at the end of it.
 */
@Composable
internal fun EmojiHandlerPanel(
    mode: EmojiMode,
    currentReaction: String? = null,
    recentEmojis: List<String>,
    onEmojiSelected: (emoji: String, size: Float) -> Unit,
    onBackspace: () -> Unit = {},
    onRecentUsed: (String) -> Unit = {},
    modifier: Modifier = Modifier
) {
    // The three hosts predate `PickerSelection` and want the emoji and its size
    // as two arguments; unpacking here is what keeps their call sites untouched.
    val onPick: (PickerSelection.Emoji) -> Unit = { pick ->
        onEmojiSelected(pick.emoji, pick.size)
        onRecentUsed(pick.emoji)
    }

    PickerPanel(
        tabs = listOf(PickerTab.EMOJI),
        modifier = modifier,
        header = {
            if (mode == EmojiMode.REACTION) {
                EmojiQuickReactions(currentReaction = currentReaction, onSelection = onPick)
            }
        },
        searchTrailing = {
            if (mode == EmojiMode.TEXT_INPUT) {
                IconButton(onClick = onBackspace, modifier = Modifier.size(36.dp)) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.Backspace,
                        contentDescription = "Backspace",
                        modifier = Modifier.size(20.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        },
    ) { _, query ->
        EmojiTab(query = query, recentEmojis = recentEmojis, onSelection = onPick)
    }
}
