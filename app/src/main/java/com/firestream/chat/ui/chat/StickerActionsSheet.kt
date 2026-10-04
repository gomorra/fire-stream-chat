package com.firestream.chat.ui.chat

import androidx.compose.foundation.clickable
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
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.firestream.chat.domain.model.Message
import com.firestream.chat.ui.components.StickerImage

/**
 * The sheet a tap on a sticker bubble opens: the sticker, and what can be done
 * with it.
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
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(bottom = 32.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            rememberMessageImageModel(message)?.let { model ->
                StickerImage(model = model, modifier = Modifier.size(120.dp))
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
