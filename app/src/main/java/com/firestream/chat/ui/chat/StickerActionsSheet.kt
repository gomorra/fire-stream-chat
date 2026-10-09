package com.firestream.chat.ui.chat

import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.GridView
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.StarBorder
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
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
import com.firestream.chat.domain.model.Message
import com.firestream.chat.domain.model.StickerFormat
import com.firestream.chat.ui.components.StickerImage

/**
 * The sheet a tap on a sticker bubble opens: the sticker, and what can be done
 * with it. The sticker is drawn a little larger than in the bubble, and a tap
 * on it enlarges it further (see [StickerPreview]).
 *
 * [isFavourite] decides which way the first row reads. [onViewPack] is null for
 * a sticker that names no pack, and the second row is left out then. Each row
 * closes the sheet.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun StickerActionsSheet(
    message: Message,
    isFavourite: Boolean,
    onToggleFavourite: () -> Unit,
    onViewPack: (() -> Unit)?,
    onDismiss: () -> Unit,
) {
    // Fully expanded, so the enlarged sticker does not push the rows below the fold.
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(bottom = 32.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            rememberMessageImageModel(message)?.let { model ->
                StickerPreview(model = model, format = message.stickerFormat)
            }
            StickerActionRows(
                isFavourite = isFavourite,
                onToggleFavourite = {
                    onToggleFavourite()
                    onDismiss()
                },
                onViewPack = onViewPack?.let { view ->
                    {
                        view()
                        onDismiss()
                    }
                },
            )
        }
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

/** The sheet's rows, apart from the sheet, so a test can draw them. */
@Composable
internal fun StickerActionRows(
    isFavourite: Boolean,
    onToggleFavourite: () -> Unit,
    onViewPack: (() -> Unit)?,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.padding(top = 8.dp)) {
        ListItem(
            headlineContent = { Text(if (isFavourite) "Remove from favourites" else "Add to favourites") },
            leadingContent = {
                Icon(if (isFavourite) Icons.Default.Star else Icons.Default.StarBorder, contentDescription = null)
            },
            colors = ListItemDefaults.colors(containerColor = Color.Transparent),
            modifier = Modifier.clickable(onClick = onToggleFavourite),
        )
        if (onViewPack != null) {
            ListItem(
                headlineContent = { Text("View pack") },
                leadingContent = { Icon(Icons.Default.GridView, contentDescription = null) },
                colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                modifier = Modifier.clickable(onClick = onViewPack),
            )
        }
    }
}
