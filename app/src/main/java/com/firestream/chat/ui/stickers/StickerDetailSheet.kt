package com.firestream.chat.ui.stickers

import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.GridView
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.StarBorder
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.min
import com.firestream.chat.domain.model.Sticker
import com.firestream.chat.domain.model.StickerFormat
import com.firestream.chat.ui.chat.STICKER_BUBBLE_SIZE
import com.firestream.chat.ui.components.StickerImage

/**
 * What [StickerDetailSheet] shows.
 *
 * [model] is what the picture is drawn from, in [format]: a file, a path or a
 * url. `null` draws no picture. [sticker] is the library's row, or `null` for a
 * sticker that is in no pack on this phone: such a sticker shows no words.
 */
@Immutable
internal data class StickerDetailState(
    val model: Any?,
    val format: StickerFormat,
    val sticker: Sticker?,
    val isFavourite: Boolean,
)

/**
 * What the rows of [StickerDetailSheet] ask for. A row whose callback is `null`
 * is left out. The star and *View pack* also call [onDismiss]. [onEdit] does
 * not: its host puts the edit in the sheet's place.
 */
@Immutable
internal data class StickerDetailCallbacks(
    val onToggleFavourite: () -> Unit = {},
    val onViewPack: (() -> Unit)? = null,
    val onEdit: (() -> Unit)? = null,
    val onDismiss: () -> Unit = {},
)

/**
 * The one sheet a tap on a sticker opens, in a chat and in the manager: the
 * sticker, what it is found by, and what can be done with it. The sticker is
 * drawn a little larger than in a bubble, and a tap on it enlarges it further
 * (see [StickerPreview]).
 *
 * The sheet only shows what its caller read. It asks nobody for a name.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun StickerDetailSheet(state: StickerDetailState, callbacks: StickerDetailCallbacks) {
    // Fully expanded, so the enlarged sticker does not push the rows below the fold.
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(onDismissRequest = callbacks.onDismiss, sheetState = sheetState) {
        StickerDetailContent(state = state, callbacks = callbacks)
    }
}

internal const val NO_NAMES_YET = "No names yet"
internal const val YOUR_WORDS_TITLE = "Your names and emojis"

/** The content of [StickerDetailSheet], apart from the sheet, so a test can draw it. */
@Composable
internal fun StickerDetailContent(
    state: StickerDetailState,
    callbacks: StickerDetailCallbacks,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(bottom = 32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        state.model?.let { model -> StickerPreview(model = model, format = state.format) }
        state.sticker?.let { sticker ->
            StickerWords(sticker = sticker, modifier = Modifier.padding(horizontal = 16.dp))
        }
        StickerActionRows(
            isFavourite = state.isFavourite,
            onToggleFavourite = {
                callbacks.onToggleFavourite()
                callbacks.onDismiss()
            },
            onViewPack = callbacks.onViewPack?.let { view ->
                {
                    view()
                    callbacks.onDismiss()
                }
            },
            onEdit = callbacks.onEdit,
        )
    }
}

/** What a library sticker is found by, read-only: the person's group, then what was found automatically. */
@Composable
private fun StickerWords(sticker: Sticker, modifier: Modifier = Modifier) {
    Column(modifier = modifier.fillMaxWidth()) {
        val found = sticker.autoName
        if (sticker.names.isEmpty() && sticker.emojis.isEmpty() && found == null) {
            Text(
                text = NO_NAMES_YET,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.fillMaxWidth().padding(top = 16.dp),
            )
            return@Column
        }
        if (sticker.names.isNotEmpty() || sticker.emojis.isNotEmpty()) {
            WordsTitle(YOUR_WORDS_TITLE)
            WordLines(
                listOfNotNull(
                    sticker.names.joinToString(" · ").ifEmpty { null },
                    sticker.emojis.joinToString(" ").ifEmpty { null },
                )
            )
        }
        if (found != null) AutomaticWordLines(found)
    }
}

/**
 * The sticker at the top of the sheet. It starts at [STICKER_PREVIEW_SIZE], a
 * little larger than the bubble. A tap enlarges it to [STICKER_ENLARGED_FRACTION]
 * of the sheet's width, at most [STICKER_ENLARGED_MAX_SIZE], and a second tap
 * shrinks it back.
 */
@Composable
internal fun StickerPreview(
    model: Any,
    format: StickerFormat,
    modifier: Modifier = Modifier,
) {
    var enlarged by rememberSaveable { mutableStateOf(false) }
    BoxWithConstraints(modifier = modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
        val enlargedSize = min(maxWidth * STICKER_ENLARGED_FRACTION, STICKER_ENLARGED_MAX_SIZE)
        val side by animateDpAsState(
            targetValue = if (enlarged) enlargedSize else STICKER_PREVIEW_SIZE,
            label = "stickerPreviewSize",
        )
        StickerImage(
            model = model,
            format = format,
            modifier = Modifier
                .testTag(STICKER_PREVIEW_TAG)
                .size(side)
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    role = Role.Image,
                    onClickLabel = if (enlarged) "Shrink sticker" else "Enlarge sticker",
                ) { enlarged = !enlarged },
        )
    }
}

internal const val STICKER_PREVIEW_TAG = "stickerPreview"

// A fifth larger than the sticker in the bubble.
internal val STICKER_PREVIEW_SIZE: Dp = STICKER_BUBBLE_SIZE * 1.2f

// Enlarged, the sticker fills most of the sheet's width but never the screen.
private const val STICKER_ENLARGED_FRACTION = 0.8f
private val STICKER_ENLARGED_MAX_SIZE: Dp = 360.dp

/**
 * The sheet's rows, apart from the sheet, so a test can draw them.
 * [isFavourite] decides which way the first row reads. [onViewPack] is null for
 * a sticker that names no pack, and [onEdit] for one that is not in the library.
 */
@Composable
internal fun StickerActionRows(
    isFavourite: Boolean,
    onToggleFavourite: () -> Unit,
    onViewPack: (() -> Unit)?,
    modifier: Modifier = Modifier,
    onEdit: (() -> Unit)? = null,
) {
    Column(modifier = modifier.padding(top = 8.dp)) {
        ActionRow(
            label = if (isFavourite) "Remove from favourites" else "Add to favourites",
            icon = { Icon(if (isFavourite) Icons.Default.Star else Icons.Default.StarBorder, contentDescription = null) },
            onClick = onToggleFavourite,
        )
        if (onViewPack != null) {
            ActionRow(
                label = "View pack",
                icon = { Icon(Icons.Default.GridView, contentDescription = null) },
                onClick = onViewPack,
            )
        }
        if (onEdit != null) {
            ActionRow(
                label = EDIT_STICKER_LABEL,
                icon = { Icon(Icons.Default.Edit, contentDescription = null) },
                onClick = onEdit,
            )
        }
    }
}

@Composable
private fun ActionRow(label: String, icon: @Composable () -> Unit, onClick: () -> Unit) {
    ListItem(
        headlineContent = { Text(label) },
        leadingContent = icon,
        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
        modifier = Modifier.clickable(onClick = onClick),
    )
}
