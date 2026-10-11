package com.firestream.chat.ui.chat

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import com.firestream.chat.domain.model.Message
import com.firestream.chat.domain.model.Sticker
import com.firestream.chat.domain.model.findSticker
import com.firestream.chat.ui.stickers.StickerDetailCallbacks
import com.firestream.chat.ui.stickers.StickerDetailSheet
import com.firestream.chat.ui.stickers.StickerDetailState
import com.firestream.chat.ui.stickers.StickerWordsSheet

/**
 * The sheet a tap on a sticker bubble opens: `StickerDetailSheet` for the
 * sticker of [message].
 *
 * What the sticker is found by comes from [overlays], the library as this phone
 * holds it. A sticker that no pack holds shows its picture, the star and
 * *View pack*, and no words and no edit. Nothing is fetched and nobody is asked
 * for a name.
 *
 * [onViewPack] is null for a sticker that names no pack. *Edit names and
 * emojis* puts the edit in the sheet's place, and [onSaveWords] gets what it
 * saved. Every way out ends in [onDismiss].
 */
@Composable
internal fun StickerActionsSheet(
    message: Message,
    overlays: OverlaysState,
    onToggleFavourite: () -> Unit,
    onViewPack: (() -> Unit)?,
    onSaveWords: (stickerId: String, emojis: List<String>, names: List<String>, stopAutoNaming: Boolean) -> Unit,
    onDismiss: () -> Unit,
) {
    val stickerId = message.stickerId
    val sticker: Sticker? = remember(stickerId, overlays.stickerPacks) {
        stickerId?.let(overlays.stickerPacks::findSticker)
    }
    var editing by rememberSaveable(message.id) { mutableStateOf(false) }

    if (editing && sticker != null) {
        StickerWordsSheet(
            sticker = sticker,
            onSave = { emojis, names, stopAutoNaming ->
                onSaveWords(sticker.id, emojis, names, stopAutoNaming)
                onDismiss()
            },
            onDismiss = onDismiss,
        )
        return
    }

    StickerDetailSheet(
        state = StickerDetailState(
            model = rememberMessageImageModel(message),
            format = message.stickerFormat,
            sticker = sticker,
            isFavourite = stickerId != null && stickerId in overlays.favouriteStickerIds,
        ),
        callbacks = StickerDetailCallbacks(
            onToggleFavourite = onToggleFavourite,
            onViewPack = onViewPack,
            // Only a sticker of the library has words to edit.
            onEdit = if (sticker != null) ({ editing = true }) else null,
            onDismiss = onDismiss,
        ),
    )
}
